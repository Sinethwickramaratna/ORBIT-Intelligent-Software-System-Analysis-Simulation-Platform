package com.orbit.backend.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds configuration files (application.yml, Dockerfile, package.json, ...) by file name and reports where each one
 * is. The names come from the {@code configuration_file} table (handed in); matching ignores case and the name that
 * is reported is the one stored in the table. Locations are paths relative to the project ({@code /} separated),
 * shallowest first. Dependency and build-output folders are skipped and symbolic links are never followed.
 */
public final class ConfigFileScanner {

    /** Locations listed per file name at most. */
    public static final int MAX_LOCATIONS_PER_FILE = 100;

    /** The outcome: configuration file name to its locations; only names found at least once, sorted by name. */
    public record Result(Map<String, List<String>> locationsByFileName) {
    }

    private final Map<String, String> displayNameByLowerName = new HashMap<>();
    private final Set<String> ignoredFolders = new HashSet<>();

    /**
     * @param fileNames      the names from {@code configuration_file}
     * @param ignoredFolders folder names to skip wherever they appear; case-insensitive
     */
    public ConfigFileScanner(Collection<String> fileNames, Set<String> ignoredFolders) {
        for (String n : fileNames) {
            String trimmed = n.trim();
            if (!trimmed.isEmpty()) {
                displayNameByLowerName.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        for (String f : ignoredFolders) {
            String n = LanguageScanner.normalizeFolder(f);
            if (!n.isEmpty()) {
                this.ignoredFolders.add(n);
            }
        }
    }

    public Result scan(Path root) throws IOException {
        return scan(root, () -> { });
    }

    /** @param onFile called once for every regular file visited; drives the progress bar */
    public Result scan(Path root, Runnable onFile) throws IOException {
        Map<String, List<String>> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && dir.getFileName() != null
                        && ignoredFolders.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                onFile.run();
                String name = displayNameByLowerName.get(file.getFileName().toString().toLowerCase(Locale.ROOT));
                if (name != null) {
                    found.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        Comparator<String> order = Comparator.comparingInt((String p) -> (int) p.chars().filter(c -> c == '/').count())
                .thenComparing(Comparator.naturalOrder());
        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        found.forEach((name, paths) -> {
            List<String> sorted = new ArrayList<>(paths);
            sorted.sort(order);
            result.put(name, List.copyOf(sorted.subList(0, Math.min(sorted.size(), MAX_LOCATIONS_PER_FILE))));
        });
        return new Result(result);
    }
}
