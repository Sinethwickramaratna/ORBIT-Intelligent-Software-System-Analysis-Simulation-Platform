package com.orbit.backend.dto.response;

import com.orbit.backend.config.DatabaseState;

/** What the first-run wizard still needs, returned by {@code GET /api/setup/status}. */
public record SetupStatus(boolean secretKeyConfigured,
                          boolean databaseCredentialsConfigured,
                          DatabaseState databaseState,
                          String databaseMessage,
                          String databaseName,
                          int databasePort,
                          boolean hasUsers,
                          boolean themeSelected,
                          String theme) {
}
