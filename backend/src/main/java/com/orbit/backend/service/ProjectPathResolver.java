package com.orbit.backend.service;

import com.orbit.backend.config.OrbitProperties;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** One folder of the user's computer that is mounted into the container. */
    public record Mount(String host, Path container) {
        /** What the user sees: the host path when known, otherwise the in-container path. */
        public String display() {
            return host;
        }
    }

    private final List<Mount> mounts;  // empty = backend runs on the user's computer

    @Autowired
    public ProjectPathResolver(OrbitProperties properties) {
        this(properties.projects() == null ? null : properties.projects().mountsOrNull(),
                properties.projects() == null ? null : properties.projects().hostRootOrNull(),
                properties.projects() == null ? null : properties.projects().containerRootOrNull());
    }

    /** Single mounted folder (kept for simplicity and tests). */
    public ProjectPathResolver(String hostRoot, String containerRoot) {
        this(null, hostRoot, containerRoot);
    }

    /**
     * @param mountSpec {@code host|container} pairs separated by commas (empty hosts are skipped), or null
     * @param hostRoot  legacy single mount: host side (may be blank = show the container path)
     * @param containerRoot legacy single mount: container side
     */
    public ProjectPathResolver(String mountSpec, String hostRoot, String containerRoot) {
        List<Mount> list = new ArrayList<>();
        if (!blank(mountSpec)) {
            for (String pair : mountSpec.split(",")) {
                int bar = pair.lastIndexOf('|');
                if (bar < 0) {
                    continue;
                }
                String host = pair.substring(0, bar);
                String container = pair.substring(bar + 1);
                if (blank(host) || blank(container)) {
                    continue; // unused slot
                }
                list.add(new Mount(tidy(host), Path.of(tidy(container)).normalize()));
            }
        } else if (!blank(containerRoot)) {
            String c = tidy(containerRoot);
            list.add(new Mount(blank(hostRoot) ? c : tidy(hostRoot), Path.of(c).normalize()));
        }
        this.mounts = List.copyOf(list);
    }

    /** True when the backend can only reach the mounted folders (Docker). */
    public boolean confined() {
        return !mounts.isEmpty();
    }

    public List<Mount> mounts() {
        return mounts;
    }

    /** Mounted folders as the user writes them (e.g. {@code E:/}); empty when anywhere is fine. */
    public List<String> visibleRoots() {
        return mounts.stream().map(Mount::display).toList();
    }

    /** The first mounted folder as shown in the UI; null when anywhere is fine. */
    public String visibleRoot() {
        return mounts.isEmpty() ? null : mounts.get(0).display();
    }

    /** The mount whose in-container folder contains {@code path}, or null. */
    public Mount mountOf(Path path) {
        Path p = path.normalize();
        Mount best = null;
        for (Mount m : mounts) {
            if (p.startsWith(m.container()) && (best == null || m.container().getNameCount() > best.container().getNameCount())) {
                best = m;
            }
        }
        return best;
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

        if (mounts.isEmpty()) {
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

        // the most specific mount that contains the typed path wins (E:/Projects before E:/)
        Mount chosen = null;
        String rel = null;
        for (Mount m : mounts) {
            String r = null;
            if (isUnder(loc, m.host())) {
                r = remainder(loc, m.host());
            } else if (isUnder(loc, tidy(m.container().toString()))) {
                r = remainder(loc, tidy(m.container().toString()));
            }
            if (r != null && (chosen == null || m.host().length() > chosen.host().length())) {
                chosen = m;
                rel = r;
            }
        }
        if (chosen == null) {
            if (!isRelative(loc)) {
                throw outside();
            }
            chosen = mounts.get(0); // a relative location goes under the first mounted folder
            rel = loc;
        }

        try {
            Path root = chosen.container();
            Path resolved = root.resolve(rel).normalize();
            if (!resolved.startsWith(root)) {
                throw outside();
            }
            String relative = root.relativize(resolved).toString().replace('\\', '/');
            String stored = relative.isEmpty() ? chosen.host() : join(chosen.host(), relative);
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
                (mounts.size() == 1
                        ? "Location must be inside " + visibleRoot() + " - that is the only folder ORBIT can access. "
                        : "Location must be inside one of the folders ORBIT can access: " + String.join(", ", visibleRoots()) + ". ")
                        + "To use another folder or drive, add it as ORBIT_MOUNT_n in .env and restart Docker.");
    }

    private static boolean isUnder(String loc, String root) {
        boolean ci = isWindowsStyle(root);
        String l = ci ? loc.toLowerCase() : loc;
        String r = ci ? root.toLowerCase() : root;
        return l.equals(r) || l.startsWith(r.endsWith("/") ? r : r + "/");
    }

    private static String join(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
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
