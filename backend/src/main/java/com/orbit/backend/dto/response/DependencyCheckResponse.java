package com.orbit.backend.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The dependency check of a project, as the Dependency Check button shows it: {@code status} INSTALLED (green check),
 * MISSING (red X) or NO_DEPENDENCIES (green: nothing declared). Paths are relative to the project folder.
 */
public record DependencyCheckResponse(UUID checkId, UUID projectId, Instant checkedAt, String status,
                                      int totalDependencies, int missingDependencies, String targetPlatform,
                                      List<Unit> units) {

    /** One manifest group; {@code missing} counts the dependencies that need setup. */
    public record Unit(String ecosystem, String ecosystemLabel, String manifest, String installLocation, String status,
                       String message, int total, int missing, List<Dependency> dependencies) {
    }

    /** {@code state}: INSTALLED, MISSING, OUTDATED, LOCAL, SDK or UNSUPPORTED. */
    public record Dependency(String name, String required, String installed, String scope, String state, String note) {
    }
}
