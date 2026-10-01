package com.orbit.backend.service;

import com.orbit.backend.dto.response.ProjectTreeResponse;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFileServiceTest {

    private final ProjectFileService service = new ProjectFileService();

    private static void file(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static List<String> names(ProjectTreeResponse r) {
        return r.entries().stream().map(e -> e.kind().name().charAt(0) + ":" + e.name()).toList();
    }

    private static void assertRejected(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void foldersComeFirstThenFilesBothAlphabeticalIgnoringCase(@TempDir Path root) throws IOException {
        file(root, "src/main.ts", "x");
        file(root, "Docs/guide.md", "x");
        Files.createDirectories(root.resolve("empty"));
        file(root, "a.txt", "12345");
        file(root, "docs.md", "x");
        file(root, "README.md", "x");
        file(root, "Zeta.txt", "x");

        ProjectTreeResponse tree = service.list(root, "");

        assertThat(names(tree)).containsExactly("F:Docs", "F:empty", "F:src", "F:a.txt", "F:docs.md", "F:README.md", "F:Zeta.txt");
        assertThat(tree.path()).isEmpty();
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    void flagsReportChildrenAndSizes(@TempDir Path root) throws IOException {
        file(root, "src/main.ts", "x");
        Files.createDirectories(root.resolve("empty"));
        file(root, "a.txt", "12345");

        var byName = service.list(root, "").entries().stream()
                .collect(java.util.stream.Collectors.toMap(ProjectTreeResponse.Entry::name, e -> e));

        assertThat(byName.get("src").hasChildren()).isTrue();
        assertThat(byName.get("src").size()).isNull();
        assertThat(byName.get("empty").hasChildren()).isFalse();
        assertThat(byName.get("a.txt").size()).isEqualTo(5L);
        assertThat(byName.get("a.txt").path()).isEqualTo("a.txt");
    }

    @Test
    void subFoldersUseRelativePaths(@TempDir Path root) throws IOException {
        file(root, "src/components/Button.tsx", "x");
        file(root, "src/main.ts", "x");

        ProjectTreeResponse src = service.list(root, "src");
        assertThat(src.entries().get(0).path()).isEqualTo("src/components");
        assertThat(src.entries().get(1).path()).isEqualTo("src/main.ts");
        assertThat(names(service.list(root, "src\\components\\"))).containsExactly("F:Button.tsx");
    }

    @Test
    void theGitFolderIsHidden(@TempDir Path root) throws IOException {
        file(root, ".git/HEAD", "ref");
        file(root, "app.js", "x");
        Files.createDirectories(root.resolve("onlygit/.git"));

        assertThat(names(service.list(root, ""))).containsExactly("F:onlygit", "F:app.js");
        assertThat(service.list(root, "").entries().get(0).hasChildren()).isFalse();
    }

    @Test
    void pathsOutsideTheProjectAreRejected(@TempDir Path root) throws IOException {
        file(root, "src/a.txt", "x");
        for (String bad : new String[]{"..", "../", "src/../..", "/etc", "C:/Windows", "src/../../x"}) {
            assertRejected(() -> service.list(root, bad), ErrorCode.PROJECT_PATH_INVALID);
        }
        assertRejected(() -> service.list(root, "missing"), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.list(root, "src/a.txt"), ErrorCode.PROJECT_PATH_INVALID);
    }

    @Test
    void aSymlinkLeadingOutsideIsNeverFollowed(@TempDir Path root, @TempDir Path outside) throws IOException {
        file(outside, "secret.txt", "s");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symbolic links are not available here");
        }

        assertRejected(() -> service.list(root, "escape"), ErrorCode.PROJECT_PATH_INVALID);
        var escape = service.list(root, "").entries().get(0);
        assertThat(escape.kind()).isEqualTo(ProjectTreeResponse.Kind.FILE);
        assertThat(escape.hasChildren()).isFalse();
    }

    @Test
    void aMissingProjectFolderIsReportedAsUnavailable(@TempDir Path root) {
        assertRejected(() -> service.list(root.resolve("gone"), ""), ErrorCode.PROJECT_FOLDER_UNAVAILABLE);
    }

    @Test
    void hugeFoldersAreCutOffAndFlagged(@TempDir Path root) throws IOException {
        for (int i = 0; i < ProjectFileService.MAX_ENTRIES + 5; i++) {
            Files.createFile(root.resolve("f" + i));
        }
        ProjectTreeResponse tree = service.list(root, "");
        assertThat(tree.entries()).hasSize(ProjectFileService.MAX_ENTRIES);
        assertThat(tree.truncated()).isTrue();
    }
}
