package com.orbit.backend.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the build systems of a project by evidence files (pom.xml, build.gradle, package.json, ...).
 *
 * <p>Like the language scanner it knows nothing concrete: the evidence ({@code build_file_type}) is handed in as a map
 * from a lower-case file name or {@code *.ext} pattern to the build system's name. Matching ignores case. A project
 * may use several build systems; every one that has at least one evidence file is reported with the evidence paths
 * (relative to the project, {@code /} separated, shallowest first). Dependency and build-output folders are skipped
 * and symbolic links are never followed.
 */
public final class BuildSystemScanner {

    /** Evidence listed per build system at most; further files are still proof but are not listed. */
    public static final int MAX_EVIDENCE_PER_SYSTEM = 50;

    /** The outcome: build system name to its evidence paths; only systems with at least one evidence file. */
    public record Result(Map<String, List<String>> evidenceByBuildSystem) {
    }

    private final Map<String, String> byFileName = new HashMap<>();
    private final Map<String, String> bySuffix = new HashMap<>();   // ".csproj" -> build system
    private final Set<String> ignoredFolders = new HashSet<>();

    /**
     * @param buildSystemByFileType file name or {@code *.ext} pattern (any case) to build system name
     * @param ignoredFolders        folder names to skip wherever they appear; case-insensitive
     */
    public BuildSystemScanner(Map<String, String> buildSystemByFileType, Set<String> ignoredFolders) {
        buildSystemByFileType.forEach((type, system) -> {
            String t = type.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("*.") && t.length() > 2) {
                bySuffix.put(t.substring(1), system);
            } else if (!t.isEmpty()) {
                byFileName.put(t, system);
            }
        });
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
                String system = systemOf(file.getFileName().toString());
                if (system != null) {
                    found.computeIfAbsent(system, k -> new ArrayList<>()).add(relative(root, file));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // shallowest first (the root build file before module build files), then alphabetical
        Comparator<String> order = Comparator.comparingInt((String p) -> (int) p.chars().filter(c -> c == '/').count())
                .thenComparing(Comparator.naturalOrder());
        found.forEach((system, paths) -> {
            List<String> sorted = new ArrayList<>(paths);
            sorted.sort(order);
            result.put(system, List.copyOf(sorted.subList(0, Math.min(sorted.size(), MAX_EVIDENCE_PER_SYSTEM))));
        });
        return new Result(result);
    }

    private String systemOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        String exact = byFileName.get(name);
        if (exact != null) {
            return exact;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? bySuffix.get(name.substring(dot)) : null;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
