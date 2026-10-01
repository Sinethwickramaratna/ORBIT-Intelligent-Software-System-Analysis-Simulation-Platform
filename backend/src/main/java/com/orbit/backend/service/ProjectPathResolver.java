package com.orbit.backend.service;

import com.orbit.backend.config.OrbitProperties;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Turns the Location a user typed (a path on THEIR computer, e.g. {@code E:\Projects\shop}) into a folder this
 * backend can really open.
 * <ul>
 *   <li><b>Backend in Docker</b> ({@code orbit.projects.container-root} set): one host folder is mounted into the
 *       container. A location inside that host folder is mapped to the mount point; a relative location is placed
 *       under it; anything else is refused, because the container cannot see it.</li>
 *   <li><b>Backend on the user's computer</b> (no roots configured): the location must be an absolute path and is
 *       used as it is.</li>
 * </ul>
 */
@Component
public class ProjectPathResolver {

    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");

    /** {@code path} is what the backend opens; {@code location} is the canonical text stored in the database. */
    public record Resolved(Path path, String location) {
    }

    private final String hostRoot;       // normalized, or null
    private final String containerRoot;  // normalized, or null

    @Autowired
    public ProjectPathResolver(OrbitProperties properties) {
        this(properties.projects() == null ? null : properties.projects().hostRootOrNull(),
                properties.projects() == null ? null : properties.projects().containerRootOrNull());
    }

    public ProjectPathResolver(String hostRoot, String containerRoot) {
        this.hostRoot = blank(hostRoot) ? null : tidy(hostRoot);
        this.containerRoot = blank(containerRoot) ? null : tidy(containerRoot);
    }

    /** The folder users must keep their projects in, as shown in the UI; null when anywhere is fine. */
    public String visibleRoot() {
        if (containerRoot == null) {
            return null;
        }
        return hostRoot != null ? hostRoot : containerRoot;
    }

    public Resolved resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID, "Location is required");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID, "Location contains invalid characters");
            }
        }
        String loc = tidy(raw);

        if (containerRoot == null) {
            try {
                Path p = Path.of(loc);
                if (!p.isAbsolute()) {
                    throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID,
                            "Location must be a full path, for example E:/Projects/my-app");
                }
                Path normalized = p.normalize();
                return new Resolved(normalized, tidy(normalized.toString()));
            } catch (InvalidPathException e) {
                throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID, "Location is not a valid path");
            }
        }

        String rel;
        if (hostRoot != null && isUnder(loc, hostRoot)) {
            rel = remainder(loc, hostRoot);
        } else if (isUnder(loc, containerRoot)) {
            rel = remainder(loc, containerRoot);
        } else if (isRelative(loc)) {
            rel = loc;
        } else {
            throw outside();
        }

        try {
            Path root = Path.of(containerRoot).normalize();
            Path resolved = root.resolve(rel).normalize();
            if (!resolved.startsWith(root)) {
                throw outside();
            }
            String relative = root.relativize(resolved).toString().replace('\\', '/');
            String base = hostRoot != null ? hostRoot : containerRoot;
            String stored = relative.isEmpty() ? base : base + "/" + relative;
            return new Resolved(resolved, stored);
        } catch (InvalidPathException e) {
            throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID, "Location is not a valid path");
        }
    }

    /** True when two stored locations point at the same folder (Windows drive paths ignore case). */
    public static boolean sameLocation(String a, String b) {
        String x = tidy(a);
        String y = tidy(b);
        return isWindowsStyle(x) || isWindowsStyle(y) ? x.equalsIgnoreCase(y) : x.equals(y);
    }

    private ApiException outside() {
        return new ApiException(ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT,
                "Location must be inside " + visibleRoot() + " - that is the only folder ORBIT can access. "
                        + "To use another folder, set ORBIT_PROJECTS_DIR in .env and restart Docker.");
    }

    private static boolean isUnder(String loc, String root) {
        boolean ci = isWindowsStyle(root);
        String l = ci ? loc.toLowerCase() : loc;
        String r = ci ? root.toLowerCase() : root;
        return l.equals(r) || l.startsWith(r.endsWith("/") ? r : r + "/");
    }

    private static String remainder(String loc, String root) {
        String rest = loc.substring(root.length());
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return rest;
    }

    private static boolean isRelative(String loc) {
        return !loc.startsWith("/") && !DRIVE.matcher(loc).matches();
    }

    private static boolean isWindowsStyle(String s) {
        return DRIVE.matcher(s).matches();
    }

    /** Trim, '\' to '/', collapse repeated '/', drop a trailing '/', upper-case a drive letter. */
    static String tidy(String raw) {
        String s = raw.trim().replace('\\', '/');
        boolean unc = s.startsWith("//");
        s = s.replaceAll("/{2,}", "/");
        if (unc) {
            s = "/" + s;
        }
        while (s.length() > 1 && s.endsWith("/") && !s.matches("^[A-Za-z]:/$")) {
            s = s.substring(0, s.length() - 1);
        }
        if (DRIVE.matcher(s).matches()) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
