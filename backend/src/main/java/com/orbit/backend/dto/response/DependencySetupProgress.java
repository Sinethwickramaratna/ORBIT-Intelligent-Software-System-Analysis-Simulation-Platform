package com.orbit.backend.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Progress of Setup Dependencies for one project. {@code state}: IDLE (never ran since the backend started),
 * RUNNING, SUCCEEDED (everything installed), FAILED (finished, but something is still missing) or CANCELLED.
 * {@code percent} is 0-100; {@code failures} lists the packages that could not be set up; {@code log} the last lines.
 */
public record DependencySetupProgress(String state, String phase, int percent, int unitIndex, int unitCount,
                                      Instant startedAt, Instant finishedAt, List<String> failures, List<String> log) {
}
