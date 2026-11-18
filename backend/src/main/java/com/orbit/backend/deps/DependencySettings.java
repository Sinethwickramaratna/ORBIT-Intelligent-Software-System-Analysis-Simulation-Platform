package com.orbit.backend.deps;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the installers download from and where they look for already installed packages.
 *
 * @param npmRegistry       npm registry base URL
 * @param pypiUrl           PyPI base URL (JSON API at {@code /pypi/<name>/json})
 * @param mavenRepositories Maven repositories for Maven and Gradle projects (Maven Central first)
 * @param nugetFlatContainer NuGet v3 flat-container base URL
 * @param pubUrl            pub.dev base URL
 * @param userHome          the user's home folder, whose package caches ({@code ~/.m2}, {@code ~/.gradle},
 *                          {@code ~/.nuget}, the pub cache) also count as "installed"; null to look only in the project
 * @param maxDownloadBytes  the largest single download accepted
 * @param parallelDownloads how many downloads run at the same time
 */
public record DependencySettings(String npmRegistry, String pypiUrl, List<String> mavenRepositories,
                                 String nugetFlatContainer, String pubUrl, Path userHome, long maxDownloadBytes,
                                 int parallelDownloads) {

    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";
    public static final String GOOGLE_MAVEN = "https://maven.google.com";
    public static final String GRADLE_PLUGINS = "https://plugins.gradle.org/m2";

    public static DependencySettings defaults() {
        String home = System.getProperty("user.home");
        return new DependencySettings("https://registry.npmjs.org", "https://pypi.org",
                List.of(MAVEN_CENTRAL, GOOGLE_MAVEN, GRADLE_PLUGINS), "https://api.nuget.org/v3-flatcontainer",
                "https://pub.dev", home == null ? null : Path.of(home), 512L * 1024 * 1024, 8);
    }

    public DependencySettings withUserHome(Path home) {
        return new DependencySettings(npmRegistry, pypiUrl, mavenRepositories, nugetFlatContainer, pubUrl, home,
                maxDownloadBytes, parallelDownloads);
    }

    public DependencySettings withNpmRegistry(String url) {
        return new DependencySettings(url, pypiUrl, mavenRepositories, nugetFlatContainer, pubUrl, userHome,
                maxDownloadBytes, parallelDownloads);
    }

    String trimmed(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
