package com.orbit.backend.service;

import com.orbit.backend.config.OrbitProperties;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderSettingsServiceTest {

    private static EnvFileService env(Path file) {
        return new EnvFileService(new OrbitProperties(file.toString(), null, null, null, null));
    }

    private static FolderSettingsService service(Path envFile, ProjectPathResolver resolver) {
        return new FolderSettingsService(env(envFile), resolver);
    }

    /** What the backend sees after docker-compose started with the given list. */
    private static ProjectPathResolver docker(String... hosts) {
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < hosts.length; i++) {
            spec.append(i == 0 ? "" : ",").append(hosts[i]).append("|/mnt/host/").append(i + 1);
        }
        return new ProjectPathResolver(spec.toString(), null, null);
    }

    private static void bad(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void normalizesTypedPathsOnAnyOs() {
        assertThat(FolderSettingsService.normalize("e:\\")).isEqualTo("E:/");
        assertThat(FolderSettingsService.normalize("E:")).isEqualTo("E:/");
        assertThat(FolderSettingsService.normalize(" d:\\My Work\\ ")).isEqualTo("D:/My Work");
        assertThat(FolderSettingsService.normalize("D://a///b/./c/")).isEqualTo("D:/a/b/c");
        assertThat(FolderSettingsService.normalize("/Users/you/code/")).isEqualTo("/Users/you/code");
        assertThat(FolderSettingsService.normalize("/home/you")).isEqualTo("/home/you");
    }

    @Test
    void rejectsPathsThatWouldBreakDockerOrMeanNothing() {
        for (String badPath : new String[]{"relative/dir", "~/code", "..", "E:/a/../b", "/", "//server/share",
                "D:/a\"b", "D:/a'b", "D:/a#b", "D:/a$b", "D:/a`b", "D:/a|b", "D:/a,b", "D:/a*b", "D:/a?b", "D:/a<b", "x".repeat(301)}) {
            bad(() -> FolderSettingsService.normalize(badPath));
        }
    }

    @Test
    void validateDropsBlanksAndDuplicatesAndLimitsToEight() {
        FolderSettingsService s = service(Path.of("unused"), docker("./projects"));
        assertThat(s.validate(List.of("E:/", " ", "e:\\", "D:/Work", "/Data", "/data"))).containsExactly("E:/", "D:/Work", "/Data", "/data");
        assertThat(s.validate(null)).isEmpty();
        bad(() -> s.validate(List.of("/a", "/b", "/c", "/d", "/e", "/f", "/g", "/h", "/i")));
    }

    @Test
    void saveWritesOnlyTheMountLinesAndKeepsEverythingElse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "JWT_SECRET=abc\n# comment\nORBIT_MOUNT_1=\nORBIT_MOUNT_2=old\nDB_USERNAME=u\n");
        FolderSettingsService s = service(file, docker("./projects"));
        assertThat(s.save(List.of("e:\\", "D:/My Work"))).containsExactly("E:/", "D:/My Work");

        Map<String, String> after = env(file).readAll();
        assertThat(after).containsEntry("JWT_SECRET", "abc").containsEntry("DB_USERNAME", "u")
                .containsEntry("ORBIT_MOUNT_1", "E:/").containsEntry("ORBIT_MOUNT_2", "D:/My Work")
                .containsEntry("ORBIT_MOUNT_3", "").containsEntry("ORBIT_MOUNT_8", "")
                .containsEntry("ORBIT_MOUNTS_CONFIGURED", "true");
        assertThat(Files.readString(file)).contains("# comment");

        // saving a shorter list clears the slots that are no longer used
        s.save(List.of("/home/you/code"));
        assertThat(env(file).readAll()).containsEntry("ORBIT_MOUNT_1", "/home/you/code").containsEntry("ORBIT_MOUNT_2", "");
    }

    @Test
    void invalidListWritesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "JWT_SECRET=abc\n");
        FolderSettingsService s = service(file, docker("./projects"));
        bad(() -> s.save(List.of("E:/", "nonsense")));
        assertThat(Files.readString(file)).isEqualTo("JWT_SECRET=abc\n");
    }

    @Test
    void setupIsNeededUntilFoldersAreChosenOrSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "JWT_SECRET=abc\nORBIT_MOUNT_1=\n");
        FolderSettingsService s = service(file, docker("./projects"));
        assertThat(s.setupNeeded()).isTrue();
        s.save(List.of());                       // "skip" = keep the default folder, but remember the choice
        assertThat(s.setupNeeded()).isFalse();
        assertThat(s.desired()).containsExactly("./projects");

        Files.writeString(file, "ORBIT_MOUNT_2=D:/x\n");   // hand-edited .env counts as configured
        assertThat(s.setupNeeded()).isFalse();
        Files.writeString(file, "ORBIT_PROJECTS_DIR=E:/Projects\n"); // old setting counts too
        assertThat(s.setupNeeded()).isFalse();
    }

    @Test
    void restartIsRequiredOnlyWhenTheRunningMountsDifferFromEnv(@TempDir Path dir) {
        Path file = dir.resolve(".env");
        FolderSettingsService before = service(file, docker("./projects"));
        assertThat(before.restartRequired()).isFalse();             // nothing configured = default, same as running

        before.save(List.of("E:/", "D:/Work"));
        assertThat(before.desired()).containsExactly("E:/", "D:/Work");
        assertThat(before.restartRequired()).isTrue();              // backend still has ./projects

        FolderSettingsService after = service(file, docker("E:/", "D:/Work")); // after `docker compose up -d`
        assertThat(after.restartRequired()).isFalse();
        assertThat(service(file, docker("D:/Work", "E:/")).restartRequired()).isTrue(); // order matters (slot numbers)
        // an empty slot 1 falls back to ORBIT_PROJECTS_DIR / ./projects, like docker-compose.yml does
        after.save(List.of());
        assertThat(after.desired()).containsExactly("./projects");
        assertThat(after.restartRequired()).isTrue();
    }

    @Test
    void runningOnTheUsersComputerNeedsNoFolderSetup(@TempDir Path dir) {
        FolderSettingsService s = service(dir.resolve(".env"), new ProjectPathResolver(null, null));
        assertThat(s.setupNeeded()).isFalse();
        assertThat(s.restartRequired()).isFalse();
        assertThat(s.editable()).isFalse();
    }

    // ---- host helper (auto-apply) ----

    @Test
    void noSignalFolderMeansNoAutoApply(@TempDir Path dir) {
        FolderSettingsService s = service(dir.resolve(".env"), docker("E:/"));
        assertThat(s.autoApplyAvailable()).isFalse();
        assertThat(s.requestApply()).isFalse();
        assertThat(s.applying()).isFalse();
    }

    @Test
    void requestOnlyWrittenWhileTheHelperIsAlive(@TempDir Path dir) throws IOException {
        Path signal = Files.createDirectory(dir.resolve("signal"));
        FolderSettingsService s = service(dir.resolve(".env"), docker("E:/"));
        s.setSignalDir(signal.toString());

        assertThat(s.autoApplyAvailable()).isFalse(); // helper never started
        assertThat(s.requestApply()).isFalse();
        assertThat(signal.resolve("apply")).doesNotExist();

        Path heartbeat = Files.writeString(signal.resolve("watcher"), "now");
        assertThat(s.autoApplyAvailable()).isTrue();
        assertThat(s.requestApply()).isTrue();
        assertThat(signal.resolve("apply")).exists();
        assertThat(s.applying()).isTrue();

        Files.delete(signal.resolve("apply")); // the helper picked the request up
        assertThat(s.applying()).isFalse();

        Files.setLastModifiedTime(heartbeat, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(120)));
        assertThat(s.autoApplyAvailable()).isFalse(); // helper window was closed long ago
        assertThat(s.requestApply()).isFalse();
    }

    @Test
    void helperStateExplainsWhyAutoApplyIsNotPossible(@TempDir Path dir) throws IOException {
        FolderSettingsService s = service(dir.resolve(".env"), docker("E:/"));
        assertThat(s.helperState()).isEqualTo("NO_SIGNAL_FOLDER"); // containers older than the helper

        s.setSignalDir(dir.resolve("missing").toString());
        assertThat(s.helperState()).isEqualTo("NO_SIGNAL_FOLDER");

        Path signal = Files.createDirectory(dir.resolve("signal"));
        s.setSignalDir(signal.toString());
        assertThat(s.helperState()).isEqualTo("NOT_RUNNING");

        Files.writeString(signal.resolve("watcher"), "now");
        assertThat(s.helperState()).isEqualTo("RUNNING");
    }
}
