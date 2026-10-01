package com.orbit.backend.service;

import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The drives/folders ORBIT may open, kept in the project's .env as ORBIT_MOUNT_1..8 (docker-compose turns them into
 * bind mounts). Docker only applies a change when the containers are re-created, so besides saving this service
 * tells whether the running backend still uses the old list ({@link #restartRequired()}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FolderSettingsService {

    public static final int MAX_FOLDERS = 8;
    static final String MARKER = "ORBIT_MOUNTS_CONFIGURED";
    static final String DEFAULT_FOLDER = "./projects";

    private static final int MAX_LENGTH = 300;
    /** Characters that would mean something else to Docker Compose, the shell or the backend's own list format. */
    private static final Pattern FORBIDDEN = Pattern.compile("[\"'#$`|,*?<>\\p{Cntrl}]");
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:/.*$");

    /** Files shared with the optional host-side helper (scripts/orbit-watch.*) through the mounted signal folder. */
    static final String APPLY_FILE = "apply";
    static final String HEARTBEAT_FILE = "watcher";
    private static final Duration HEARTBEAT_MAX_AGE = Duration.ofSeconds(30);

    private final EnvFileService envFileService;
    private final ProjectPathResolver resolver;

    /** Folder shared with the host (empty when not running in Docker). Set by ORBIT_SIGNAL_DIR in docker-compose. */
    @Value("${orbit.signal-dir:}")
    private String signalDir = "";

    /** True when folders still have to be chosen (Docker only; never when the backend runs on the user's computer). */
    public boolean setupNeeded() {
        if (!resolver.confined()) {
            return false;
        }
        Map<String, String> env = envFileService.readAll();
        if ("true".equalsIgnoreCase(env.getOrDefault(MARKER, ""))) {
            return false;
        }
        for (int i = 1; i <= MAX_FOLDERS; i++) {
            if (!env.getOrDefault("ORBIT_MOUNT_" + i, "").isBlank()) {
                return false;
            }
        }
        return env.getOrDefault("ORBIT_PROJECTS_DIR", "").isBlank();
    }

    /** The list docker-compose will mount on the next start (same rules as docker-compose.yml). */
    public List<String> desired() {
        Map<String, String> env = envFileService.readAll();
        List<String> list = new ArrayList<>();
        String first = env.getOrDefault("ORBIT_MOUNT_1", "");
        if (first.isBlank()) {
            first = env.getOrDefault("ORBIT_PROJECTS_DIR", "");
        }
        list.add(ProjectPathResolver.tidy(first.isBlank() ? DEFAULT_FOLDER : first));
        for (int i = 2; i <= MAX_FOLDERS; i++) {
            String v = env.getOrDefault("ORBIT_MOUNT_" + i, "");
            if (!v.isBlank()) {
                list.add(ProjectPathResolver.tidy(v));
            }
        }
        return list;
    }

    public List<String> active() {
        return resolver.visibleRoots();
    }

    public boolean restartRequired() {
        return resolver.confined() && !desired().equals(active());
    }

    /** True while the host helper that re-creates the containers is running (it touches a heartbeat file). */
    public boolean autoApplyAvailable() {
        Path heartbeat = signalFile(HEARTBEAT_FILE);
        if (heartbeat == null) {
            return false;
        }
        try {
            return Duration.between(Files.getLastModifiedTime(heartbeat).toInstant(), Instant.now())
                    .compareTo(HEARTBEAT_MAX_AGE) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Why automatic apply is (not) possible: {@code RUNNING}; {@code NOT_RUNNING} (the helper on the user's computer
     * is stopped); or {@code NO_SIGNAL_FOLDER} (these containers were created before the helper existed, so a
     * one-time start.cmd / start.sh is needed to update them).
     */
    public String helperState() {
        if (signalDir == null || signalDir.isBlank() || !Files.isDirectory(Path.of(signalDir))) {
            return "NO_SIGNAL_FOLDER";
        }
        return autoApplyAvailable() ? "RUNNING" : "NOT_RUNNING";
    }

    /** True while a request to re-create the containers is waiting for (or being run by) the host helper. */
    public boolean applying() {
        Path request = signalFile(APPLY_FILE);
        return request != null && Files.exists(request);
    }

    /**
     * Asks the host helper to run {@code docker compose up -d} so the new folders get mounted. Returns false when no
     * helper is running (the user then has to run start.cmd / start.sh once) or the request could not be written.
     */
    public boolean requestApply() {
        Path request = signalFile(APPLY_FILE);
        if (request == null || !autoApplyAvailable()) {
            return false;
        }
        try {
            Files.writeString(request, Instant.now().toString());
            log.info("Asked the host helper to re-create the containers so the new folders are mounted");
            return true;
        } catch (IOException e) {
            log.warn("Could not write {}: {}", request, e.toString());
            return false;
        }
    }

    void setSignalDir(String dir) {
        this.signalDir = dir == null ? "" : dir;
    }

    private Path signalFile(String name) {
        if (signalDir == null || signalDir.isBlank()) {
            return null;
        }
        return Path.of(signalDir).resolve(name);
    }

    public boolean editable() {
        return resolver.confined();
    }

    /** Checks and normalizes the typed entries; throws {@code VALIDATION_FAILED} naming the first bad one. */
    public List<String> validate(List<String> raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue; // an empty row in the form
            }
            String folder = normalize(entry);
            boolean duplicate = result.stream().anyMatch(f -> f.equalsIgnoreCase(folder) && (isDrivePath(f) || f.equals(folder)));
            if (!duplicate) {
                result.add(folder);
            }
        }
        if (result.size() > MAX_FOLDERS) {
            throw bad("You can add at most " + MAX_FOLDERS + " folders");
        }
        return result;
    }

    /** Validates and writes ORBIT_MOUNT_1..8 (unused slots emptied) to .env. Returns the saved list. */
    public synchronized List<String> save(List<String> raw) {
        List<String> folders = validate(raw);
        Map<String, String> updates = new LinkedHashMap<>();
        for (int i = 1; i <= MAX_FOLDERS; i++) {
            updates.put("ORBIT_MOUNT_" + i, i <= folders.size() ? folders.get(i - 1) : "");
        }
        updates.put(MARKER, "true");
        envFileService.update(updates);
        log.info("Folders ORBIT may open saved to .env: {}", folders);
        return folders;
    }

    static String normalize(String entry) {
        String s = entry.strip();
        if (s.length() > MAX_LENGTH) {
            throw bad("'" + shorten(s) + "' is too long");
        }
        if (FORBIDDEN.matcher(s.replace('\\', '/')).find()) {
            throw bad("'" + shorten(s) + "' contains a character that is not allowed (\" ' # $ ` | , * ? < >)");
        }
        s = s.replace('\\', '/');
        if (s.startsWith("//")) {
            throw bad("Network paths (\\\\server\\share) cannot be used - map the share to a drive letter first");
        }
        boolean drive = DRIVE_PATH.matcher(s).matches() || s.matches("^[A-Za-z]:$");
        if (!drive && !s.startsWith("/")) {
            throw bad("'" + shorten(s) + "' must be a full path such as E:/ , D:/Work , /Users/you/code or /home/you/code");
        }
        String prefix = drive ? Character.toUpperCase(s.charAt(0)) + ":" : "";
        String rest = drive ? s.substring(2) : s;
        List<String> parts = new ArrayList<>();
        for (String seg : rest.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                throw bad("'" + shorten(s) + "' must not contain '..'");
            }
            parts.add(seg);
        }
        if (parts.isEmpty()) {
            if (!drive) {
                throw bad("The whole file system ('/') cannot be used - choose a folder such as /home/you/code");
            }
            return prefix + "/"; // a whole drive
        }
        return prefix + "/" + String.join("/", parts);
    }

    private static boolean isDrivePath(String s) {
        return DRIVE_PATH.matcher(s).matches();
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static ApiException bad(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
