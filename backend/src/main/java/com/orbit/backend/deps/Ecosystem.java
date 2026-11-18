package com.orbit.backend.deps;

/**
 * A family of dependency manifests that one installer handles. {@link #NATIVE} stands for C/C++ build systems (CMake,
 * Make, Meson): their dependencies are system libraries without a package registry, so ORBIT reports them but has
 * nothing to download.
 */
public enum Ecosystem {
    NPM("npm", "Node.js (npm)"),
    PYTHON("pip", "Python (pip)"),
    MAVEN("maven", "Java (Maven)"),
    GRADLE("gradle", "Java / Kotlin (Gradle)"),
    NUGET("nuget", ".NET (NuGet)"),
    PUB("pub", "Dart / Flutter (pub)"),
    NATIVE("native", "C / C++ (CMake, Make, Meson)");

    private final String id;
    private final String label;

    Ecosystem(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** Stable lower-case id, stored in the database and used by the UI. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Ecosystem fromId(String id) {
        for (Ecosystem e : values()) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown ecosystem " + id);
    }
}
