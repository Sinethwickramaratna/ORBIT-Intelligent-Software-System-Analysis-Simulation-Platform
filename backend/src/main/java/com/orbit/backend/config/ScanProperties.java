package com.orbit.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings of the project scan ({@code orbit.scan.*}).
 *
 * <p>{@code ignoredFolders} are folders that hold dependencies or build output rather than the project's own source
 * code; they are skipped everywhere in the project so they cannot distort the language percentages. Setting the list
 * in the configuration replaces these defaults entirely.
 */
@ConfigurationProperties(prefix = "orbit.scan")
public record ScanProperties(List<String> ignoredFolders, Long maxFileBytes) {

    public static final List<String> DEFAULT_IGNORED_FOLDERS = List.of(
            "node_modules", ".git", "target", "build", "dist", "venv", "__pycache__",
            // same kind of folder under another name: Python virtualenv, Next.js and Gradle output
            ".venv", ".next", ".gradle");

    /** Files bigger than this are minified or generated bundles, not hand-written source. */
    public static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024;

    public List<String> ignoredFoldersOrDefault() {
        return ignoredFolders == null || ignoredFolders.isEmpty() ? DEFAULT_IGNORED_FOLDERS : ignoredFolders;
    }

    public long maxFileBytesOrDefault() {
        return maxFileBytes == null || maxFileBytes <= 0 ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
    }
}
