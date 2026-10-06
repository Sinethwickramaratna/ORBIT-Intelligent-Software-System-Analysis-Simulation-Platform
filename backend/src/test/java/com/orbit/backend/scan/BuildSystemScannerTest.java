package com.orbit.backend.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSystemScannerTest {

    private static final Map<String, String> EVIDENCE = Map.of(
            "pom.xml", "Maven", "build.gradle", "Gradle", "settings.gradle", "Gradle",
            "package.json", "npm/Node ecosystem", "requirements.txt", "Python dependency management",
            "*.csproj", ".NET (MSBuild)");
    private static final Set<String> IGNORED = Set.of("node_modules/", "target/");

    private static void file(Path root, String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    void detectsSeveralBuildSystemsWithTheirEvidence(@TempDir Path root) throws IOException {
        file(root, "pom.xml");
        file(root, "service/pom.xml");
        file(root, "build.gradle");
        file(root, "settings.gradle");
        file(root, "web/package.json");
        file(root, "web/node_modules/x/package.json");   // ignored
        file(root, "tools/Requirements.TXT");             // case-insensitive
        file(root, "app/App.csproj");                     // *.ext pattern

        var result = new BuildSystemScanner(EVIDENCE, IGNORED).scan(root).evidenceByBuildSystem();

        assertThat(result.keySet()).containsExactly(
                ".NET (MSBuild)", "Gradle", "Maven", "npm/Node ecosystem", "Python dependency management");
        assertThat(result.get("Maven")).containsExactly("pom.xml", "service/pom.xml");
        assertThat(result.get("Gradle")).containsExactly("build.gradle", "settings.gradle");
        assertThat(result.get("npm/Node ecosystem")).containsExactly("web/package.json");
    }

    @Test
    void projectWithoutBuildFilesHasNoBuildSystems(@TempDir Path root) throws IOException {
        file(root, "src/a.py");
        assertThat(new BuildSystemScanner(EVIDENCE, IGNORED).scan(root).evidenceByBuildSystem()).isEmpty();
    }

    @Test
    void capsTheListedEvidenceAndReportsEveryFileToProgress(@TempDir Path root) throws IOException {
        for (int i = 0; i < 70; i++) {
            file(root, "m" + i + "/pom.xml");
        }
        AtomicInteger visited = new AtomicInteger();
        var result = new BuildSystemScanner(EVIDENCE, IGNORED).scan(root, visited::incrementAndGet);
        assertThat(result.evidenceByBuildSystem().get("Maven")).hasSize(BuildSystemScanner.MAX_EVIDENCE_PER_SYSTEM);
        assertThat(visited.get()).isEqualTo(70).isEqualTo(FileCounter.count(root, IGNORED));
    }

    @Test
    void newEvidenceRowsAreUsedWithoutCodeChanges(@TempDir Path root) throws IOException {
        file(root, "build.sbt");
        assertThat(new BuildSystemScanner(Map.of("build.sbt", "sbt"), IGNORED).scan(root).evidenceByBuildSystem())
                .containsOnlyKeys("sbt");
    }
}
