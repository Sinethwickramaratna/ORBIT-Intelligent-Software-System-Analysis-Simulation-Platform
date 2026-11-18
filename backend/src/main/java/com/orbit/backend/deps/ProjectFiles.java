package com.orbit.backend.deps;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The dependency manifests of a project folder. Installed dependencies, build output and virtual environments are
 * never entered (and links are never followed), so a project that already has its node_modules is read as quickly as
 * one without.
 */
public final class ProjectFiles {

    /** Folders that hold installed dependencies or build output, never the project's own manifests. */
    public static final Set<String> SKIPPED = Set.of("node_modules", ".git", ".hg", ".svn", "target", "build", "dist",
            "out", ".venv", "venv", "__pycache__", ".next", ".nuxt", ".gradle", ".orbit", ".dart_tool", ".pub-cache",
            "bin", "obj", ".idea", ".vs", ".vscode", ".mvn", ".tox", ".nox", "site-packages", "bower_components",
            ".terraform", ".cache", ".pytest_cache", ".mypy_cache");

    private static final int MAX_DEPTH = 12;
    private static final int MAX_ENTRIES = 300_000;

    private final Path root;
    private final List<String> files;

    private ProjectFiles(Path root, List<String> files) {
        this.root = root;
        this.files = files;
    }

    public Path root() {
        return root;
    }

    /** Relative ({@code /} separated) paths of every manifest found. */
    public List<String> all() {
        return files;
    }

    public List<String> named(String... names) {
        Set<String> wanted = Set.of(names);
        return files.stream().filter(f -> wanted.contains(fileName(f).toLowerCase(Locale.ROOT))).toList();
    }

    public static String fileName(String relative) {
        return relative.substring(relative.lastIndexOf('/') + 1);
    }

    public static String dirOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    static boolean interesting(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("package.json") || n.equals("package-lock.json") || n.equals("npm-shrinkwrap.json")
                || (n.startsWith("requirements") && n.endsWith(".txt")) || n.equals("pyproject.toml") || n.equals("pipfile")
                || n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts")
                || n.equals("settings.gradle") || n.equals("settings.gradle.kts") || n.equals("libs.versions.toml")
                || n.endsWith(".csproj") || n.endsWith(".fsproj") || n.endsWith(".vbproj") || n.equals("packages.config")
                || n.equals("directory.packages.props") || n.equals("pubspec.yaml") || n.equals("pubspec.lock")
                || n.equals("cmakelists.txt") || n.equals("makefile") || n.equals("gnumakefile") || n.equals("meson.build")
                || n.equals("vcpkg.json") || n.equals("conanfile.txt");
    }

    public static ProjectFiles scan(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<String> found = new ArrayList<>();
        int[] seen = {0};
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base)) {
                    String name = dir.getFileName().toString();
                    if (SKIPPED.contains(name.toLowerCase(Locale.ROOT)) || Files.exists(dir.resolve("pyvenv.cfg"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return ++seen[0] > MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && interesting(file.getFileName().toString())) {
                    found.add(base.relativize(file).toString().replace('\\', '/'));
                }
                return ++seen[0] > MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort((a, b) -> {
            int da = a.split("/").length;
            int db = b.split("/").length;
            return da != db ? Integer.compare(da, db) : a.compareTo(b);
        });
        return new ProjectFiles(base, List.copyOf(found));
    }

    /** Reads a text file of the project (BOM removed); null when it cannot be read or is unreasonably big. */
    public String read(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            return null;
        }
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 16L * 1024 * 1024) {
                return null;
            }
            String s = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
            return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code a/b} + {@code ../c} style join of relative paths, {@code ""} = the project folder. */
    public static String join(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }
}
