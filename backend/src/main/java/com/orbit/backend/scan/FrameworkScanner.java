package com.orbit.backend.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detects frameworks from dependency metadata: every dependency manifest that the build-system and configuration
 * stages found (pom.xml, build.gradle, package.json, requirements.txt, ...) is parsed by {@link DependencyReader};
 * each dependency name is compared with the catalog taken from the {@code framework_dependency} table. A hit is a
 * framework, and the manifest, line and column of the dependency are its evidence. Source code is never searched.
 *
 * <p>A catalog entry only matches a manifest of its own package manager, so {@code next} in a requirements.txt does
 * not make a Next.js project. An entry ending in {@code *} is a prefix rule ({@code spring-boot-*}). Nothing about
 * frameworks is hard-coded here.
 */
public final class FrameworkScanner {

    /** Evidence listed per framework at most. */
    public static final int MAX_EVIDENCE_PER_FRAMEWORK = 50;

    /** One row of the {@code framework_dependency} table joined with its framework name. */
    public record CatalogEntry(String frameworkName, String dependencyName, String packageManager) {
    }

    /** Where a framework was proven: the manifest, the dependency as written there and its 1-based position. */
    public record Evidence(String filePath, int line, int column, String dependency) {
    }

    /** The outcome: framework name to its evidence; only frameworks found at least once, sorted by name. */
    public record Result(Map<String, List<Evidence>> evidenceByFramework, int manifestsRead) {
    }

    private record Rule(String frameworkName, String name, boolean prefix) {
    }

    // package manager -> exact dependency name -> rule, and package manager -> prefix rules (longest first)
    private final Map<String, Map<String, Rule>> exact = new HashMap<>();
    private final Map<String, List<Rule>> prefixes = new HashMap<>();
    private final long maxFileBytes;

    /**
     * @param catalog      the rows of {@code framework_dependency}
     * @param maxFileBytes manifests bigger than this are skipped
     */
    public FrameworkScanner(Collection<CatalogEntry> catalog, long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
        for (CatalogEntry e : catalog) {
            String pm = e.packageManager().trim().toLowerCase(Locale.ROOT);
            String name = DependencyReader.normalize(pm, e.dependencyName());
            if (name.isEmpty()) {
                continue;
            }
            if (name.endsWith("*")) {
                String stem = name.substring(0, name.length() - 1);
                if (!stem.isEmpty()) {
                    prefixes.computeIfAbsent(pm, k -> new ArrayList<>()).add(new Rule(e.frameworkName(), stem, true));
                }
            } else {
                exact.computeIfAbsent(pm, k -> new HashMap<>()).putIfAbsent(name, new Rule(e.frameworkName(), name, false));
            }
        }
        prefixes.values().forEach(list -> list.sort(Comparator.comparingInt((Rule r) -> r.name().length()).reversed()));
    }

    /** The framework a dependency of this package manager belongs to, if the catalog knows it. */
    Optional<String> match(String packageManager, String dependencyName) {
        String name = DependencyReader.normalize(packageManager, dependencyName);
        Rule hit = exact.getOrDefault(packageManager, Map.of()).get(name);
        if (hit == null) {
            for (Rule r : prefixes.getOrDefault(packageManager, List.of())) {
                if (name.startsWith(r.name())) {
                    hit = r;
                    break;
                }
            }
        }
        return Optional.ofNullable(hit).map(Rule::frameworkName);
    }

    /** Which of these relative paths are manifests this scanner can read. */
    public static List<String> manifestsAmong(Collection<String> relativePaths) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : relativePaths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (DependencyReader.packageManagerOf(name).isPresent()) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /**
     * @param root      the project folder
     * @param manifests relative paths ({@code /} separated) of the dependency files found by the earlier stages
     * @param onFile    called once for every manifest handled; drives the progress bar
     */
    public Result scan(Path root, Collection<String> manifests, Runnable onFile) {
        Map<String, List<Evidence>> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int read = 0;
        for (String relative : new LinkedHashSet<>(manifests)) {
            onFile.run();
            String fileName = relative.substring(relative.lastIndexOf('/') + 1);
            Optional<String> pm = DependencyReader.packageManagerOf(fileName);
            if (pm.isEmpty()) {
                continue;
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) {
                continue;
            }
            String content;
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maxFileBytes) {
                    continue;
                }
                content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            if (!content.isEmpty() && content.charAt(0) == '﻿') {
                content = content.substring(1);
            }
            Optional<DependencyReader.Reading> reading = DependencyReader.read(fileName, content);
            if (reading.isEmpty()) {
                continue;
            }
            read++;
            for (DependencyReader.Dependency d : reading.get().dependencies()) {
                match(reading.get().packageManager(), d.name()).ifPresent(framework ->
                        found.computeIfAbsent(framework, k -> new ArrayList<>())
                                .add(new Evidence(relative, d.line(), d.column(), d.name())));
            }
        }

        Comparator<Evidence> order = Comparator
                .comparingInt((Evidence e) -> (int) e.filePath().chars().filter(c -> c == '/').count())
                .thenComparing(Evidence::filePath)
                .thenComparingInt(Evidence::line)
                .thenComparingInt(Evidence::column);
        Map<String, List<Evidence>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        found.forEach((framework, list) -> {
            List<Evidence> sorted = new ArrayList<>(list);
            sorted.sort(order);
            result.put(framework, List.copyOf(sorted.subList(0, Math.min(sorted.size(), MAX_EVIDENCE_PER_FRAMEWORK))));
        });
        return new Result(result, read);
    }
}
