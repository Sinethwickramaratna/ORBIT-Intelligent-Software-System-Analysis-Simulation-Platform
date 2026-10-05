package com.orbit.backend.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageScannerTest {

    private static final Map<String, String> EXTENSIONS = Map.of(
            ".java", "Java", ".py", "Python", ".js", "JavaScript", ".ts", "TypeScript", ".tsx", "TypeScript");
    private static final Set<String> IGNORED = Set.of("node_modules/", ".git/", "target/", "build/", "dist/", "venv/", "__pycache__/");

    private static void file(Path root, String rel, String line, int times) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, (line + "\n").repeat(times));
    }

    private final LanguageScanner scanner = new LanguageScanner(EXTENSIONS, IGNORED, 2L * 1024 * 1024);

    @Test
    void countsFilesAndSourceLinesPerLanguage(@TempDir Path root) throws IOException {
        file(root, "src/App.java", "int a;", 7800);
        file(root, "web/app.ts", "const a = 1;", 1000);
        file(root, "web/view.tsx", "const b = 2;", 500);
        file(root, "tools/job.py", "x = 1", 700);
        file(root, "README.md", "not code", 5000);

        LanguageScanner.Result result = scanner.scan(root);

        assertThat(result.byLanguage()).containsOnlyKeys("Java", "Python", "TypeScript");
        assertThat(result.byLanguage().get("Java")).isEqualTo(new LanguageScanner.Stat(1, 7800));
        assertThat(result.byLanguage().get("TypeScript")).isEqualTo(new LanguageScanner.Stat(2, 1500));
        assertThat(result.byLanguage().get("Python")).isEqualTo(new LanguageScanner.Stat(1, 700));
    }

    @Test
    void ignoredFoldersAreSkippedAtAnyDepthAndIgnoringCase(@TempDir Path root) throws IOException {
        file(root, "src/Main.java", "int a;", 10);
        for (String dir : List.of("node_modules/lib", "web/node_modules/dep", "target/classes", "build", "dist", "venv/lib",
                "pkg/__pycache__", ".git/hooks", "Build")) {
            file(root, dir + "/Skipped.java", "int g;", 9000);
            file(root, dir + "/skipped.js", "var g;", 9000);
        }

        LanguageScanner.Result result = scanner.scan(root);

        assertThat(result.byLanguage()).containsOnlyKeys("Java");
        assertThat(result.byLanguage().get("Java")).isEqualTo(new LanguageScanner.Stat(1, 10));
    }

    @Test
    void extensionsAreMatchedIgnoringCaseAndNamesWithoutExtensionAreIgnored(@TempDir Path root) throws IOException {
        file(root, "Upper/CASE.JAVA", "int a;", 2);
        file(root, "Makefile", "all:", 1);
        file(root, "weird.", "x", 1);
        file(root, "backup.java.bak", "int a;", 1);

        assertThat(scanner.scan(root).byLanguage()).containsOnlyKeys("Java");
        assertThat(scanner.scan(root).byLanguage().get("Java")).isEqualTo(new LanguageScanner.Stat(1, 2));
    }

    @Test
    void filesAboveTheSizeLimitAreSkippedAndReported(@TempDir Path root) throws IOException {
        file(root, "huge.py", "x = 1", 1000);
        LanguageScanner tiny = new LanguageScanner(EXTENSIONS, IGNORED, 100);

        LanguageScanner.Result result = tiny.scan(root);

        assertThat(result.byLanguage()).isEmpty();
        assertThat(result.skippedFiles()).isEqualTo(1);
    }

    @Test
    void emptyFolderGivesAnEmptyResult(@TempDir Path root) throws IOException {
        assertThat(scanner.scan(root).byLanguage()).isEmpty();
    }
}
