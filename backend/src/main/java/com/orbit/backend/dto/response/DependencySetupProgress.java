package com.orbit.backend.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Progress of a dependency check or of Setup Dependencies for one project ({@code kind} CHECK or SETUP).
 * {@code state}: IDLE (nothing ran since the backend started), RUNNING, SUCCEEDED (everything installed), FAILED
 * (finished, but something is still missing) or CANCELLED. {@code percent} is 0-100. {@code steps} has one entry per
 * manifest group; {@code current} is the package being worked on; {@code installed} / {@code failed} count packages;
 * {@code failures} says what could not be set up; {@code log} holds the last lines of the activity log.
 */
public record DependencySetupProgress(String kind, String state, String phase, String current, int percent,
                                      int unitIndex, int unitCount, List<Step> steps, int installed, int failed,
                                      List<Change> changes, Instant startedAt, Instant finishedAt, List<String> failures,
                                      List<String> log) {

    /**
     * A version ORBIT changed in a project file because the requested one could not be installed
     * ({@code requirements.txt}: {@code pyyaml ==5.3.1 -> ==6.0.2}).
     */
    public record Change(String file, String dependency, String from, String to, String reason) {
    }

    /** One manifest group: {@code state} PENDING, RUNNING, DONE or FAILED; {@code summary} once it has finished. */
    public record Step(String title, String state, String summary) {
    }
}
