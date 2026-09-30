package com.orbit.backend.setup;

import com.orbit.backend.config.OrbitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the project's {@code .env} file (per-machine settings such as JWT_SECRET, DB_USERNAME, DB_PASSWORD).
 * The file is rewritten <em>in place</em> (same inode) so it also works when it is a single-file Docker bind mount
 * that the PostgreSQL container is watching.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvFileService {

    private final OrbitProperties properties;

    public synchronized void update(Map<String, String> values) {
        Path file = Path.of(properties.envFile()).toAbsolutePath().normalize();
        write(file, values);
        log.info("Saved {} to {}", values.keySet(), file);
    }

    /** Replaces {@code KEY=...} lines (or appends them) and keeps every other line untouched. */
    static void write(Path file, Map<String, String> values) {
        values.forEach((k, v) -> {
            if (v.contains("\n") || v.contains("\r") || k.contains("=") || k.contains("\n")) {
                throw new IllegalArgumentException("Illegal characters in env entry " + k);
            }
        });
        if (Files.isDirectory(file)) {
            throw new UncheckedIOException(new IOException(
                    file + " is a directory, not a file. Docker creates a folder when ./.env does not exist before "
                            + "'docker compose up'. Stop the stack, delete the .env folder, then start with start.cmd "
                            + "(or copy .env.example to .env first)."));
        }
        try {
            List<String> lines = Files.exists(file)
                    ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            values.forEach((key, value) -> {
                String entry = key + "=" + value;
                boolean replaced = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(key + "=")) {
                        lines.set(i, entry);
                        replaced = true;
                    }
                }
                if (!replaced) {
                    lines.add(entry);
                }
            });
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // default options: CREATE + TRUNCATE_EXISTING + WRITE -> same inode, in place
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
