package com.orbit.backend.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFileScannerTest {

    private static final List<String> NAMES = List.of(
            "application.yml", "application.properties", "Dockerfile", "docker-compose.yml", "package.json", "requirements.txt");
    private static final Set<String> IGNORED = Set.of("node_modules/", "target/");

    private static void file(Path root, String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    void reportsEveryConfigurationFileWithItsLocation(@TempDir Path root) throws IOException {
        file(root, "backend/src/main/resources/application.yml");
        file(root, "Dockerfile");
        file(root, "backend/Dockerfile");
        file(root, "docker/dockerfile");                 // case-insensitive, reported under the stored name
        file(root, "web/package.json");
        file(root, "web/node_modules/x/package.json");   // ignored
        file(root, "docs/Dockerfile.md");                // not an exact file name

        var result = new ConfigFileScanner(NAMES, IGNORED).scan(root).locationsByFileName();

        assertThat(result.keySet()).containsExactly("application.yml", "Dockerfile", "package.json");
        assertThat(result.get("Dockerfile")).containsExactly("Dockerfile", "backend/Dockerfile", "docker/dockerfile");
        assertThat(result.get("application.yml")).containsExactly("backend/src/main/resources/application.yml");
    }

    @Test
    void capsLocationsAndCountsEveryFileForProgress(@TempDir Path root) throws IOException {
        for (int i = 0; i < 120; i++) {
            file(root, "m" + i + "/Dockerfile");
        }
        int[] visited = {0};
        var result = new ConfigFileScanner(NAMES, IGNORED).scan(root, () -> visited[0]++);
        assertThat(result.locationsByFileName().get("Dockerfile")).hasSize(ConfigFileScanner.MAX_LOCATIONS_PER_FILE);
        assertThat(visited[0]).isEqualTo(120).isEqualTo(FileCounter.count(root, IGNORED));
    }

    @Test
    void emptyTableOrNoMatchesFindNothing(@TempDir Path root) throws IOException {
        file(root, "src/a.py");
        assertThat(new ConfigFileScanner(NAMES, IGNORED).scan(root).locationsByFileName()).isEmpty();
        assertThat(new ConfigFileScanner(List.of(), IGNORED).scan(root).locationsByFileName()).isEmpty();
    }
}
