package com.orbit.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings of the dependency check and Setup Dependencies ({@code orbit.dependencies.*}). Everything has a default;
 * normally only {@code host-os} / {@code host-arch} are worth setting, when ORBIT runs in Docker and cannot tell the
 * user's computer from the mounted folders.
 *
 * @param hostOs            windows, mac or linux: the computer the downloaded packages are for (empty = detect)
 * @param hostArch          x64 or arm64 (empty = detect)
 * @param pythonVersion     Python version assumed when a project has no virtual environment yet (default 3.12)
 * @param npmRegistry       npm registry URL
 * @param pypiUrl           PyPI URL
 * @param mavenRepositories Maven repositories for Maven and Gradle projects
 * @param nugetUrl          NuGet v3 flat-container URL
 * @param pubUrl            pub.dev URL
 * @param parallelDownloads downloads running at the same time
 * @param maxDownloadMb     largest single download accepted
 * @param userCaches        also count packages in the user's own caches (~/.m2, ~/.nuget, ...) as installed
 */
@ConfigurationProperties(prefix = "orbit.dependencies")
public record DependencyProperties(String hostOs, String hostArch, String pythonVersion, String npmRegistry, String pypiUrl,
                                   List<String> mavenRepositories, String nugetUrl, String pubUrl, Integer parallelDownloads,
                                   Integer maxDownloadMb, Boolean userCaches) {
}
