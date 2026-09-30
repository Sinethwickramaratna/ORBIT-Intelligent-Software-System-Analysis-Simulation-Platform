package com.orbit.backend.setup;

import com.orbit.backend.common.exception.ApiException;
import com.orbit.backend.common.exception.ErrorCode;
import com.orbit.backend.database.DatabaseProvisioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * First-run environment setup: validates the values the user typed into the setup screen, saves them to the
 * project's {@code .env} (JWT_SECRET, DB_USERNAME, DB_PASSWORD) and activates them. Values that are already
 * configured can never be overwritten through this API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    static final int MIN_SECRET_LENGTH = 32;
    static final int MIN_DB_PASSWORD_LENGTH = 8;
    static final int MAX_LENGTH = 128;

    /**
     * Characters that mean the same thing to every consumer of the .env file (Spring's properties loader, Docker
     * Compose and the shell script that starts PostgreSQL): no whitespace, quotes, backslash, '$' or '#'.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9!@%^&*()_+\\-=\\[\\]{};:,.<>?/~|]+$");
    private static final Pattern DB_USERNAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    private final SecretKeyService secretKeyService;
    private final DatabaseProvisioner databaseProvisioner;
    private final EnvFileService envFileService;

    public synchronized void configureEnvironment(String secretKey, String dbUsername, String dbPassword) {
        boolean secretMissing = !secretKeyService.isConfigured();
        boolean dbMissing = !databaseProvisioner.hasCredentials();
        if (!secretMissing && !dbMissing) {
            throw new ApiException(ErrorCode.ENVIRONMENT_ALREADY_CONFIGURED);
        }

        Map<String, String> updates = new LinkedHashMap<>();

        boolean secretGiven = notBlank(secretKey);
        if (secretGiven && !secretMissing) {
            throw new ApiException(ErrorCode.ENVIRONMENT_ALREADY_CONFIGURED, "The secret key is already configured");
        }
        if (secretMissing) {
            if (!secretGiven) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "The secret key is required");
            }
            validateSecret(secretKey.trim());
            updates.put("JWT_SECRET", secretKey.trim());
        }

        boolean dbGiven = notBlank(dbUsername) || notBlank(dbPassword);
        if (dbGiven && !dbMissing) {
            throw new ApiException(ErrorCode.ENVIRONMENT_ALREADY_CONFIGURED, "The database credentials are already configured");
        }
        if (dbMissing) {
            if (!notBlank(dbUsername) || !notBlank(dbPassword)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "The database username and password are required");
            }
            validateDbUsername(dbUsername.trim());
            validateDbPassword(dbPassword.trim());
            updates.put("DB_USERNAME", dbUsername.trim());
            updates.put("DB_PASSWORD", dbPassword.trim());
        }

        envFileService.update(updates);
        if (secretMissing) {
            secretKeyService.activate(secretKey.trim());
        }
        if (dbMissing) {
            databaseProvisioner.configure(dbUsername.trim(), dbPassword.trim());
        }
        log.info("Environment configured: {}", updates.keySet());
    }

    private static void validateSecret(String value) {
        if (value.length() < MIN_SECRET_LENGTH) {
            throw new ApiException(ErrorCode.SECRET_KEY_TOO_WEAK);
        }
        requireSafe(value, "The secret key", MAX_LENGTH);
    }

    private static void validateDbUsername(String value) {
        if (!DB_USERNAME.matcher(value).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The database username must start with a letter or '_' and contain only letters, digits and '_' (max 63)");
        }
    }

    private static void validateDbPassword(String value) {
        if (value.length() < MIN_DB_PASSWORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The database password must be at least " + MIN_DB_PASSWORD_LENGTH + " characters");
        }
        requireSafe(value, "The database password", MAX_LENGTH);
    }

    private static void requireSafe(String value, String what, int max) {
        if (value.length() > max || !SAFE_VALUE.matcher(value).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    what + " may only contain letters, digits and !@%^&*()_+-=[]{};:,.<>?/~| (no spaces, quotes, '$', '#' or '\\')");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
