package com.orbit.backend.deps;

/** The state of one declared dependency after a check. */
public enum DependencyState {
    /** Present in the project folder (or the user's own package cache) in a version the manifest accepts. */
    INSTALLED(false),
    /** Not present: Setup Dependencies downloads it. */
    MISSING(true),
    /** Present, but in a version the manifest does not accept: Setup Dependencies replaces it. */
    OUTDATED(true),
    /** Part of the project itself (a module, workspace package or local path): nothing to download. */
    LOCAL(false),
    /** Comes with an SDK (for example {@code flutter: sdk: flutter}): installed with the SDK, not downloaded. */
    SDK(false),
    /** Missing, and declared in a way ORBIT cannot download (git URL, private file, ...). */
    UNSUPPORTED(true);

    private final boolean needsSetup;

    DependencyState(boolean needsSetup) {
        this.needsSetup = needsSetup;
    }

    /** True when this dependency keeps the project from being "all installed". */
    public boolean needsSetup() {
        return needsSetup;
    }
}
