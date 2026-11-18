package com.orbit.backend.deps;

import java.util.List;

/**
 * Receives what a dependency check or Setup Dependencies is doing, for the progress window. Implementations must be
 * thread safe (installers download in parallel).
 */
public interface SetupProgress {

    /** The manifest groups that will be worked on, in order (called once, before the first {@link #unit}). */
    default void plan(List<String> titles) {
    }

    /** A manifest group starts ({@code index} is 0-based). */
    void unit(String title, int index, int total);

    /** A manifest group is finished; {@code ok} is false when something in it failed, {@code summary} says what happened. */
    default void unitDone(int index, boolean ok, String summary) {
    }

    /** Progress inside the current group: {@code done} of {@code total} packages ({@code total} 0 = unknown). */
    void step(String text, int done, int total);

    /** One package was installed ({@code ok}) or could not be ({@code !ok}). */
    default void item(String what, boolean ok) {
    }

    /**
     * A manifest of the project was changed because the version it asked for could not be installed
     * ({@code requirements.txt}: {@code numpy ==1.21.0 -> ==1.26.4}).
     */
    default void manifestChanged(String file, String dependency, String from, String to, String reason) {
    }

    /** A line for the activity log. */
    void log(String line);

    /** True when the user cancelled the setup. */
    default boolean cancelled() {
        return false;
    }

    /**
     * Registers what to run the moment the user cancels (abort the downloads in flight, stop the download threads), so
     * a cancel takes effect at once instead of after the current files. Runs at once when already cancelled.
     */
    default void onCancel(Runnable action) {
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
