package com.orbit.backend.service;

import com.orbit.backend.dto.response.ProjectTreeResponse;
import com.orbit.backend.dto.response.ProjectTreeResponse.Entry;
import com.orbit.backend.dto.response.ProjectTreeResponse.Kind;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reads a project folder one level at a time (like Solution Explorer) without ever leaving the project root. */
@Service
public class ProjectFileService {

    /** More entries than this in one folder are cut off and reported as {@code truncated}. */
    static final int MAX_ENTRIES = 2000;
    private static final String GIT_DIR = ".git";

    public ProjectTreeResponse list(Path projectRoot, String relativePath) {
        Path rootReal = realRoot(projectRoot);
        String rel = cleanRelative(relativePath);
        Path target = rel.isEmpty() ? rootReal : rootReal.resolve(rel).normalize();
        Path targetReal;
        try {
            targetReal = target.toRealPath(); // follows symlinks, so an escaping link is caught below
        } catch (NoSuchFileException e) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "That folder does not exist in the project");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE);
        }
        if (!targetReal.startsWith(rootReal)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
        }
        if (!Files.isDirectory(targetReal)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "That path is not a folder");
        }

        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetReal)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.equals(GIT_DIR)) {
                    continue; // git's own bookkeeping is hidden, like in Visual Studio
                }
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(entryFor(child, name, rel));
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE);
        }

        entries.sort(Comparator
                .comparing((Entry e) -> e.kind() == Kind.FOLDER ? 0 : 1)
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::name));
        return new ProjectTreeResponse(rel, entries, truncated);
    }

    /** Throws PROJECT_FOLDER_UNAVAILABLE when the root is missing; returns the real (symlink-free) root. */
    private Path realRoot(Path projectRoot) {
        try {
            Path real = projectRoot.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
            }
            return real;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
        }
    }

    private Entry entryFor(Path child, String name, String parentRel) {
        String path = parentRel.isEmpty() ? name : parentRel + "/" + name;
        // NOFOLLOW: a symlink to a folder is shown as a plain entry and is never expanded
        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            return new Entry(name, path, Kind.FOLDER, null, hasVisibleChildren(child));
        }
        Long size = null;
        try {
            size = Files.size(child);
        } catch (IOException ignored) {
            // unreadable size: show the file without one
        }
        return new Entry(name, path, Kind.FILE, size, false);
    }

    private boolean hasVisibleChildren(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!p.getFileName().toString().equals(GIT_DIR)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // unreadable folder: treat as empty
        }
        return false;
    }

    /** Relative path from the client: '/'-separated, no '..', not absolute, no control characters. */
    static String cleanRelative(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
            }
        }
        if (s.startsWith("/") || s.matches("^[A-Za-z]:.*")) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
        }
        List<String> parts = new ArrayList<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }
}
