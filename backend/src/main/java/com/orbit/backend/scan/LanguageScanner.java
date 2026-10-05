package com.orbit.backend.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walks a project folder and, per programming language, counts the files and their source-code lines.
 *
 * <p>The scanner knows nothing about concrete languages: which file extension belongs to which language is handed
 * in (it comes from the {@code language_extensions} table), as is the list of folders to skip (dependency and build
 * output such as {@code node_modules/} or {@code target/}). Symbolic links are never followed, unreadable files are
 * skipped, and files above a size limit (minified or generated bundles) are left out so they cannot distort the
 * result.
 */
public final class LanguageScanner {

    /** Files and source-code lines found for one language. */
    public record Stat(int files, int lines) {
    }

    /** The outcome of a scan: language name to its totals (only languages with at least one file), sorted by name. */
    public record Result(Map<String, Stat> byLanguage, int skippedFiles) {
    }

    /** Comment and string rules of the languages that are not C-like; every other language is treated as C-like. */
    private static final Map<String, LineCounter.Style> STYLE_BY_LANGUAGE = Map.of(
            "python", LineCounter.Style.HASH,
            "sql", LineCounter.Style.SQL,
            "c#", LineCounter.Style.C_SHARP,
            "dart", LineCounter.Style.DART);

    private final Map<String, String> languageByExtension;
    private final Set<String> ignoredFolders;
    private final long maxFileBytes;

    /**
     * @param languageByExtension lower-case extension including the dot ({@code ".java"}) to language name
     * @param ignoredFolders      folder names to skip wherever they appear ({@code "node_modules"}); case-insensitive
     * @param maxFileBytes        files larger than this are skipped
     */
    public LanguageScanner(Map<String, String> languageByExtension, Set<String> ignoredFolders, long maxFileBytes) {
        Map<String, String> ext = new HashMap<>();
        languageByExtension.forEach((k, v) -> ext.put(normalizeExtension(k), v));
        this.languageByExtension = Map.copyOf(ext);
        Set<String> ignored = new HashSet<>();
        for (String folder : ignoredFolders) {
            String name = normalizeFolder(folder);
            if (!name.isEmpty()) {
                ignored.add(name);
            }
        }
        this.ignoredFolders = Set.copyOf(ignored);
        this.maxFileBytes = maxFileBytes;
    }

    public Result scan(Path root) throws IOException {
        Map<String, int[]> totals = new TreeMap<>();   // language -> {files, lines}
        int[] skipped = {0};

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
                    return FileVisitResult.CONTINUE; // symbolic links and special files are never followed
                }
                String language = languageByExtension.get(extensionOf(file.getFileName().toString()));
                if (language == null) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > maxFileBytes) {
                    skipped[0]++;
                    return FileVisitResult.CONTINUE;
                }
                try {
                    int lines = countLines(file, language);
                    int[] t = totals.computeIfAbsent(language, k -> new int[2]);
                    t[0]++;
                    t[1] = (int) Math.min(Integer.MAX_VALUE, (long) t[1] + lines);
                } catch (IOException | RuntimeException e) {
                    skipped[0]++; // unreadable file: leave it out
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                skipped[0]++;
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, Stat> byLanguage = new TreeMap<>();
        totals.forEach((language, t) -> byLanguage.put(language, new Stat(t[0], t[1])));
        return new Result(byLanguage, skipped[0]);
    }

    private static int countLines(Path file, String language) throws IOException {
        LineCounter.Style style = STYLE_BY_LANGUAGE.getOrDefault(language.toLowerCase(Locale.ROOT), LineCounter.Style.C_LIKE);
        // InputStreamReader replaces malformed input instead of throwing, so odd encodings still count
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            return LineCounter.countCodeLines(reader, style);
        }
    }

    /** {@code "Main.JAVA"} becomes {@code ".java"}; a name without a dot has no extension (empty string). */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Accepts {@code "java"}, {@code ".java"} and {@code ".JAVA"}. */
    public static String normalizeExtension(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith(".") ? s : "." + s;
    }

    /** Accepts {@code "node_modules/"}, {@code "/node_modules"} and {@code "Node_Modules"}. */
    static String normalizeFolder(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
