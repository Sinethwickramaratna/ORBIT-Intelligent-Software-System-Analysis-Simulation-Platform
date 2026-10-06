package com.orbit.backend.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Counts the regular files a scan is going to visit (same folder rules as the scanners), so the progress bar knows
 * what "100 %" means.
 */
public final class FileCounter {

    private FileCounter() {
    }

    public static int count(Path root, Set<String> ignoredFolders) throws IOException {
        Set<String> ignored = new HashSet<>();
        for (String f : ignoredFolders) {
            String n = LanguageScanner.normalizeFolder(f);
            if (!n.isEmpty()) {
                ignored.add(n);
            }
        }
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && dir.getFileName() != null
                        && ignored.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && count[0] < Integer.MAX_VALUE) {
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }
}
