package com.orbit.backend.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvFileServiceTest {

    @Test
    void createsMissingFile(@TempDir Path dir) throws Exception {
        Path env = dir.resolve("nested/.env");
        EnvFileService.write(env, Map.of("JWT_SECRET", "abc"));
        assertThat(Files.readAllLines(env)).containsExactly("JWT_SECRET=abc");
    }

    @Test
    void replacesEmptySampleValuesAndKeepsEverythingElse(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.write(env, List.of("# sample", "JWT_SECRET=", "DB_USERNAME=", "DB_PASSWORD=", "", "DB_NAME=orbit", "DB_PORT=5433"));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("JWT_SECRET", "s3cret");
        values.put("DB_USERNAME", "sineth");
        values.put("DB_PASSWORD", "pw12345678");
        EnvFileService.write(env, values);

        assertThat(Files.readAllLines(env)).containsExactly(
                "# sample", "JWT_SECRET=s3cret", "DB_USERNAME=sineth", "DB_PASSWORD=pw12345678", "", "DB_NAME=orbit", "DB_PORT=5433");
    }

    @Test
    void appendsKeysThatAreNotInTheFileYet(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.write(env, List.of("DB_NAME=orbit"));
        EnvFileService.write(env, Map.of("DB_USERNAME", "u"));
        assertThat(Files.readAllLines(env)).containsExactly("DB_NAME=orbit", "DB_USERNAME=u");
    }

    @Test
    void rewritesInPlaceKeepingTheSameFile(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.write(env, List.of("JWT_SECRET="));
        Object before = Files.readAttributes(env, "fileKey").get("fileKey");
        EnvFileService.write(env, Map.of("JWT_SECRET", "x"));
        Object after = Files.readAttributes(env, "fileKey").get("fileKey");
        assertThat(after).as("same inode, so a Docker single-file bind mount keeps seeing updates").isEqualTo(before);
    }

    @Test
    void rejectsNewlineInjection(@TempDir Path dir) {
        assertThatThrownBy(() -> EnvFileService.write(dir.resolve(".env"), Map.of("DB_PASSWORD", "a\nJWT_SECRET=evil")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
