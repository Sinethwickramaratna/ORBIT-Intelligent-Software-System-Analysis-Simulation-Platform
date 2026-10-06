package com.orbit.backend.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers how far the scan of each project is, so the UI can show a progress bar while the (synchronous) scan
 * request is still running. A scan has four stages: counting the files, detecting languages (70 % of the bar),
 * detecting build systems (15 %) and detecting configuration files (the last 15 %). The state is in memory only and removed when the scan ends.
 */
@Component
public class ScanProgressTracker {

    public static final String COUNTING = "Counting files";
    public static final String LANGUAGES = "Detecting languages";
    public static final String BUILD_SYSTEMS = "Detecting build systems";
    public static final String CONFIGURATION = "Detecting configuration files";

    /** What the progress endpoint returns. */
    public record Progress(boolean active, String phase, int percent) {
        static final Progress IDLE = new Progress(false, null, 0);
    }

    /** Handle of one running scan; the scanners call {@link #fileVisited()} for every file. */
    public final class Run {
        private final UUID projectId;
        private volatile String phase = COUNTING;
        private volatile int base;
        private volatile int span;
        private volatile int total = 1;
        private volatile int done;
        private volatile int percent;

        private Run(UUID projectId) {
            this.projectId = projectId;
        }

        /** Starts a stage that covers {@code span} percent of the bar beginning at {@code base}. */
        public void stage(String name, int base, int span, int total) {
            this.total = Math.max(total, 1);
            this.done = 0;
            this.base = base;
            this.span = span;
            this.phase = name;
            this.percent = Math.max(this.percent, base);
        }

        public void fileVisited() {
            done++;
        }

        Progress snapshot() {
            int p = base + (int) ((long) span * Math.min(done, total) / total);
            // never moves backwards, never reaches 100 before the scan really ended
            percent = Math.max(percent, Math.min(p, 99));
            return new Progress(true, phase, percent);
        }

        /** Removes the run (only if it is still the registered one). */
        public void finish() {
            runs.remove(projectId, this);
        }
    }

    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public Run start(UUID projectId) {
        Run run = new Run(projectId);
        runs.put(projectId, run);
        return run;
    }

    public Progress current(UUID projectId) {
        Run run = runs.get(projectId);
        return run == null ? Progress.IDLE : run.snapshot();
    }
}
