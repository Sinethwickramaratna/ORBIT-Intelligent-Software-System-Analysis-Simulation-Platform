package com.orbit.backend.service;

import com.orbit.backend.config.DatabaseState;
import com.orbit.backend.config.DynamicDataSource;
import com.orbit.backend.config.OrbitProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the database lifecycle:
 * <ol>
 *   <li>credentials come from {@code DB_USERNAME} / {@code DB_PASSWORD} (.env) - entered by the user on first run;
 *       host, name and port default to localhost / orbit / 5433;</li>
 *   <li>waits for PostgreSQL to accept them (the container only starts once the credentials exist);</li>
 *   <li>creates the connection pool and runs the Flyway migrations, which initialise an empty database
 *       (users, refresh_token_table, app_settings) and leave an existing one untouched.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseProvisioner {

    private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(5);
    private static final long RETRY_DELAY_MS = 2000;

    private final OrbitProperties properties;
    private final DynamicDataSource dataSource;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orbit-db-provisioner");
        t.setDaemon(true);
        return t;
    });

    private volatile String username;
    private volatile String password;
    private volatile DatabaseState state = DatabaseState.NOT_CONFIGURED;
    private volatile String message = "Database credentials have not been entered yet";
    private volatile HikariDataSource pool;

    @PostConstruct
    void init() {
        OrbitProperties.Database db = properties.database();
        this.username = db.username();
        this.password = db.password();
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        if (hasCredentials()) {
            startConnecting();
        } else {
            log.warn("No database credentials yet - waiting for first-run setup");
        }
    }

    public boolean hasCredentials() {
        OrbitProperties.Database db = properties.database();
        if (db.url() != null && !db.url().isBlank()) {
            return true; // explicit JDBC URL override
        }
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public boolean isReady() {
        return state == DatabaseState.READY;
    }

    public DatabaseState getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public String getDatabaseName() {
        return properties.database().nameOrDefault();
    }

    public int getPort() {
        try {
            return Integer.parseInt(properties.database().portOrDefault());
        } catch (NumberFormatException e) {
            return 5433;
        }
    }

    /** Called after the setup screen saved the credentials to .env. */
    public void configure(String newUsername, String newPassword) {
        this.username = newUsername;
        this.password = newPassword;
        startConnecting();
    }

    /** Lets the user retry after a failure (e.g. PostgreSQL was still starting). */
    public void retry() {
        if (state == DatabaseState.FAILED && hasCredentials()) {
            startConnecting();
        }
    }

    private void startConnecting() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        state = DatabaseState.CONNECTING;
        message = "Waiting for PostgreSQL...";
        executor.submit(() -> {
            try {
                connectAndMigrate();
            } catch (RuntimeException e) {
                fail("Unexpected error while preparing the database: " + e.getMessage());
                log.error("Database provisioning failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    private void connectAndMigrate() {
        String url = jdbcUrl();
        String where = properties.database().hostOrDefault() + ":" + properties.database().portOrDefault();
        Instant deadline = Instant.now().plus(GIVE_UP_AFTER);
        int attempt = 0;

        while (true) {
            attempt++;
            try (Connection ignored = DriverManager.getConnection(url, username, password)) {
                break;
            } catch (SQLException e) {
                String sqlState = e.getSQLState() == null ? "" : e.getSQLState();
                if (sqlState.equals("28P01") || sqlState.equals("28000")) {
                    fail("PostgreSQL rejected the username/password. The database was created with different "
                            + "credentials - restore them in .env, or run 'docker compose down -v' to start fresh.");
                    return;
                }
                if (sqlState.equals("3D000")) {
                    fail("Database '" + getDatabaseName() + "' does not exist on " + where + ".");
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    fail("Could not reach PostgreSQL at " + where + ": " + e.getMessage());
                    return;
                }
                message = "Waiting for PostgreSQL at " + where + " (attempt " + attempt + ")...";
                log.debug("{} [{}]", message, sqlState);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting for PostgreSQL");
                    return;
                }
            }
        }

        HikariDataSource newPool = null;
        try {
            message = "Preparing the database schema...";
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setPoolName("orbit-pool");
            cfg.setMaximumPoolSize(10);
            newPool = new HikariDataSource(cfg);

            Flyway.configure()
                    .dataSource(newPool)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load()
                    .migrate();

            HikariDataSource old = this.pool;
            this.pool = newPool;
            dataSource.setTarget(newPool);
            if (old != null) {
                old.close();
            }
            message = "Database ready";
            state = DatabaseState.READY;
            log.info("Database ready at {} (database '{}')", where, getDatabaseName());
        } catch (Exception e) {
            if (newPool != null) {
                newPool.close();
            }
            fail("Could not prepare the database schema: " + e.getMessage());
            log.error("Flyway migration failed", e);
        }
    }

    private String jdbcUrl() {
        OrbitProperties.Database db = properties.database();
        if (db.url() != null && !db.url().isBlank()) {
            return db.url();
        }
        return "jdbc:postgresql://" + db.hostOrDefault() + ":" + db.portOrDefault() + "/" + db.nameOrDefault();
    }

    private void fail(String reason) {
        message = reason;
        state = DatabaseState.FAILED;
        log.error("Database not available: {}", reason);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        if (pool != null) {
            pool.close();
        }
    }
}
