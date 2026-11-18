package com.orbit.backend.deps;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safe extraction of downloaded archives. Entries are only ever written below the target folder (no {@code ..},
 * no absolute paths), links and device files are skipped, and the unpacked size is capped, so a hostile archive
 * cannot write elsewhere on the user's disk or fill it up.
 */
public final class Archives {

    private static final long MAX_UNPACKED = 2L * 1024 * 1024 * 1024;

    private Archives() {
    }

    /**
     * Unpacks a {@code .tgz}. {@code rename} maps the entry name to the relative output path (or null to skip it);
     * npm packages, for example, strip their first folder ({@code package/}).
     */
    public static void untarGz(Path archive, Path target, Function<String, String> rename) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw))) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (!(e.isFile() || e.isDirectory())) {
                    continue; // symbolic/hard links, devices
                }
                String name = rename.apply(e.getName().replace('\\', '/'));
                if (name == null || name.isBlank()) {
                    continue;
                }
                Path out = safe(root, name);
                if (out == null) {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                total += e.getSize();
                if (total > MAX_UNPACKED) {
                    throw new IOException("Archive is too large when unpacked: " + archive.getFileName());
                }
                Files.createDirectories(out.getParent());
                Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Unpacks a zip ({@code .whl}, {@code .nupkg}); {@code rename} as for {@link #untarGz}. */
    public static void unzip(Path archive, Path target, Function<String, String> rename) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zip.getNextEntry()) != null) {
                String name = rename.apply(e.getName().replace('\\', '/'));
                if (name == null || name.isBlank()) {
                    continue;
                }
                Path out = safe(root, name);
                if (out == null) {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var os = Files.newOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_UNPACKED) {
                            throw new IOException("Archive is too large when unpacked: " + archive.getFileName());
                        }
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** The output path, or null when the name would escape the target folder. */
    static Path safe(Path root, String name) {
        String n = name;
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        if (n.isEmpty() || n.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String part : n.split("/")) {
            if (part.equals("..")) {
                return null;
            }
        }
        Path out = root.resolve(n).normalize();
        return out.startsWith(root) && !out.equals(root) ? out : null;
    }

    /** npm style: drop the first path segment ({@code package/index.js} -> {@code index.js}). */
    public static String stripFirst(String name) {
        int slash = name.indexOf('/');
        return slash < 0 ? null : name.substring(slash + 1);
    }

    /** Deletes a folder tree (best effort, links are removed, not followed). */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(dir);
            return;
        }
        try (var walk = Files.walk(dir)) {
            var list = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : list) {
                Files.deleteIfExists(p);
            }
        }
    }
}
