package com.orbit.backend.deps;

/** Receives what Setup Dependencies is doing, for the progress bar. Implementations must be thread safe. */
public interface SetupProgress {

    /** A new manifest group starts ({@code index} is 0-based). */
    void unit(String title, int index, int total);

    /** Progress inside the current group: {@code done} of {@code total} packages. */
    void step(String text, int done, int total);

    /** A line for the setup log. */
    void log(String line);

    /** True when the user cancelled the setup. */
    default boolean cancelled() {
        return false;
    }

    SetupProgress NONE = new SetupProgress() {
        @Override
        public void unit(String title, int index, int total) {
        }

        @Override
        public void step(String text, int done, int total) {
        }

        @Override
        public void log(String line) {
        }
    };
}
