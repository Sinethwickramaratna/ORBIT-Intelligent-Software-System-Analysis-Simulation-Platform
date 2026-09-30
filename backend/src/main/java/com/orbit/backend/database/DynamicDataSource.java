package com.orbit.backend.database;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A {@link DataSource} whose real connection pool is attached later, once the user has entered the database
 * credentials in the first-run setup screen. Until then {@link #getConnection()} fails fast with SQLState 08001,
 * which lets the application (and the setup endpoints) start without any database.
 */
public class DynamicDataSource extends AbstractDataSource {

    private volatile DataSource target;

    void setTarget(DataSource target) {
        this.target = target;
    }

    public boolean isAttached() {
        return target != null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current().getConnection(username, password);
    }

    private DataSource current() throws SQLException {
        DataSource t = target;
        if (t == null) {
            throw new SQLException("The database is not ready yet", "08001");
        }
        return t;
    }
}
