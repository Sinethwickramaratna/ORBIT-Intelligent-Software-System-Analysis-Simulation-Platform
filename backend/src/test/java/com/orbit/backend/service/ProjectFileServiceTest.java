package com.orbit.backend.service;

import com.orbit.backend.dto.response.FileContentResponse;
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

    // ---------------------------------------------------------------- editing

    @Test
    void readFileReturnsTextWithLineEndingAndBomFlags(@TempDir Path root) throws IOException {
        file(root, "src/a.ts", "let x = 1;\r\nlet y = 'é';\r\n");
        Files.write(root.resolve("bom.txt"), new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'});

        FileContentResponse a = service.readFile(root, "src/a.ts");
        assertThat(a.content()).isEqualTo("let x = 1;\r\nlet y = 'é';\r\n");
        assertThat(a.lineEnding()).isEqualTo("CRLF");
        assertThat(a.bom()).isFalse();
        assertThat(a.name()).isEqualTo("a.ts");
        assertThat(a.path()).isEqualTo("src/a.ts");

        FileContentResponse b = service.readFile(root, "bom.txt");
        assertThat(b.content()).isEqualTo("hi");
        assertThat(b.bom()).isTrue();
        assertThat(b.lineEnding()).isEqualTo("LF");
    }

    @Test
    void readFileRefusesBinaryNonUtf8FoldersAndMissingFiles(@TempDir Path root) throws IOException {
        Files.write(root.resolve("img.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        Files.write(root.resolve("latin1.txt"), new byte[]{'c', 'a', 'f', (byte) 0xE9});
        Files.createDirectories(root.resolve("dir"));

        assertRejected(() -> service.readFile(root, "img.png"), ErrorCode.FILE_NOT_TEXT);
        assertRejected(() -> service.readFile(root, "latin1.txt"), ErrorCode.FILE_NOT_TEXT);
        assertRejected(() -> service.readFile(root, "dir"), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.readFile(root, "nope.txt"), ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void readFileRefusesFilesThatAreTooLarge(@TempDir Path root) throws IOException {
        try (var raf = new java.io.RandomAccessFile(root.resolve("big.log").toFile(), "rw")) {
            raf.setLength(ProjectFileService.MAX_EDIT_BYTES + 1);
        }
        assertRejected(() -> service.readFile(root, "big.log"), ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void saveFileWritesUtf8KeepsBomAndDetectsChangesOnDisk(@TempDir Path root) throws IOException {
        file(root, "notes.md", "old");
        long opened = service.readFile(root, "notes.md").lastModified();

        FileContentResponse saved = service.saveFile(root, "notes.md", "new ✓\n", false, opened);
        assertThat(Files.readString(root.resolve("notes.md"))).isEqualTo("new ✓\n");
        assertThat(saved.size()).isEqualTo(Files.size(root.resolve("notes.md")));
        assertThat(saved.content()).isNull();

        service.saveFile(root, "notes.md", "x", true, null);
        assertThat(Files.readAllBytes(root.resolve("notes.md"))).containsExactly(0xEF - 256, 0xBB - 256, 0xBF - 256, 'x');

        Files.setLastModifiedTime(root.resolve("notes.md"), java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        assertRejected(() -> service.saveFile(root, "notes.md", "lost?", false, 2_000_000L), ErrorCode.FILE_CHANGED_ON_DISK);
        assertThat(Files.readAllBytes(root.resolve("notes.md"))).hasSize(4);
    }

    @Test
    void saveFileOnlyWritesExistingFilesInsideTheProject(@TempDir Path root) {
        assertRejected(() -> service.saveFile(root, "missing.txt", "x", false, null), ErrorCode.FILE_NOT_FOUND);
        assertRejected(() -> service.saveFile(root, "../outside.txt", "x", false, null), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.saveFile(root, "", "x", false, null), ErrorCode.PROJECT_PATH_INVALID);
        assertThat(root.resolveSibling("outside.txt")).doesNotExist();
    }

    @Test
    void createFileAndCreateFolderInRootAndInsideAFolder(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));

        var f = service.create(root, "", "README.md", ProjectTreeResponse.Kind.FILE);
        var d = service.create(root, "src", "components", ProjectTreeResponse.Kind.FOLDER);
        var inner = service.create(root, "src/components", "App.tsx", ProjectTreeResponse.Kind.FILE);

        assertThat(root.resolve("README.md")).isRegularFile().isEmptyFile();
        assertThat(root.resolve("src/components")).isDirectory();
        assertThat(root.resolve("src/components/App.tsx")).isRegularFile();
        assertThat(f.path()).isEqualTo("README.md");
        assertThat(d.path()).isEqualTo("src/components");
        assertThat(d.kind()).isEqualTo(ProjectTreeResponse.Kind.FOLDER);
        assertThat(inner.path()).isEqualTo("src/components/App.tsx");
        assertThat(inner.kind()).isEqualTo(ProjectTreeResponse.Kind.FILE);
    }

    @Test
    void createRefusesANameThatAlreadyExists(@TempDir Path root) throws IOException {
        file(root, "a.txt", "keep");
        assertRejected(() -> service.create(root, "", "a.txt", ProjectTreeResponse.Kind.FILE), ErrorCode.FILE_ALREADY_EXISTS);
        assertRejected(() -> service.create(root, "", "a.txt", ProjectTreeResponse.Kind.FOLDER), ErrorCode.FILE_ALREADY_EXISTS);
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("keep");
    }

    @Test
    void createRefusesInvalidNamesAndParentsOutsideTheProject(@TempDir Path root) {
        for (String bad : new String[]{"", "  ", "a/b", "a\\b", "..", ".", "x:y", "what?", "end.", "CON", "nul.txt", ".git", "a\u0001"}) {
            assertRejected(() -> service.create(root, "", bad, ProjectTreeResponse.Kind.FILE), ErrorCode.FILE_NAME_INVALID);
        }
        assertRejected(() -> service.create(root, "..", "x.txt", ProjectTreeResponse.Kind.FILE), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.create(root, "missing", "x.txt", ProjectTreeResponse.Kind.FILE), ErrorCode.FILE_NOT_FOUND);
        assertRejected(() -> service.create(root, ".git", "x.txt", ProjectTreeResponse.Kind.FILE), ErrorCode.PROJECT_PATH_INVALID);
    }

    @Test
    void validNameKeepsDotsSpacesAndUnicode() {
        assertThat(ProjectFileService.validName(" my file.test.ts ")).isEqualTo("my file.test.ts");
        assertThat(ProjectFileService.validName(".env")).isEqualTo(".env");
        assertThat(ProjectFileService.validName("résumé")).isEqualTo("résumé");
    }

    @Test
    void renameMovesFilesAndFoldersWithinTheirFolder(@TempDir Path root) throws IOException {
        file(root, "src/old.ts", "code");
        file(root, "lib/x.txt", "x");

        var f = service.rename(root, "src/old.ts", "new.ts");
        var d = service.rename(root, "lib", "library");

        assertThat(root.resolve("src/old.ts")).doesNotExist();
        assertThat(Files.readString(root.resolve("src/new.ts"))).isEqualTo("code");
        assertThat(f.path()).isEqualTo("src/new.ts");
        assertThat(Files.readString(root.resolve("library/x.txt"))).isEqualTo("x");
        assertThat(d.path()).isEqualTo("library");
        assertThat(d.hasChildren()).isTrue();
    }

    @Test
    void renameChangesLetterCaseOnly(@TempDir Path root) throws IOException {
        file(root, "readme.md", "r");
        var e = service.rename(root, "readme.md", "README.md");
        assertThat(e.name()).isEqualTo("README.md");
        try (var s = Files.list(root)) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("README.md");
        }
    }

    @Test
    void renameRefusesExistingTargetsInvalidNamesAndTheRoot(@TempDir Path root) throws IOException {
        file(root, "a.txt", "a");
        file(root, "b.txt", "b");
        assertRejected(() -> service.rename(root, "a.txt", "b.txt"), ErrorCode.FILE_ALREADY_EXISTS);
        assertRejected(() -> service.rename(root, "a.txt", "../a.txt"), ErrorCode.FILE_NAME_INVALID);
        assertRejected(() -> service.rename(root, "", "x"), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.rename(root, "nope.txt", "x"), ErrorCode.FILE_NOT_FOUND);
        assertThat(Files.readString(root.resolve("b.txt"))).isEqualTo("b");
    }

    @Test
    void deleteRemovesFilesAndWholeFolders(@TempDir Path root) throws IOException {
        file(root, "a.txt", "a");
        file(root, "src/deep/inner/x.ts", "x");
        file(root, "src/y.ts", "y");
        root.resolve("src/y.ts").toFile().setReadOnly();

        service.delete(root, "a.txt");
        service.delete(root, "src");

        assertThat(root.resolve("a.txt")).doesNotExist();
        assertThat(root.resolve("src")).doesNotExist();
        assertThat(root).isDirectory();
    }

    @Test
    void deleteRefusesTheProjectRootGitAndPathsOutside(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        assertRejected(() -> service.delete(root, ""), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.delete(root, "/"), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.delete(root, ".git"), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.delete(root, "../x"), ErrorCode.PROJECT_PATH_INVALID);
        assertRejected(() -> service.delete(root, "missing"), ErrorCode.FILE_NOT_FOUND);
        assertThat(root.resolve(".git")).isDirectory();
    }

    @Test
    void deleteOfALinkLeadingOutsideRemovesOnlyTheLink(@TempDir Path root, @TempDir Path outside) throws IOException {
        file(outside, "keep.txt", "k");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symbolic links are not available here");
        }
        assertRejected(() -> service.readFile(root, "escape/keep.txt"), ErrorCode.PROJECT_PATH_INVALID);
        service.delete(root, "escape");
        assertThat(root.resolve("escape")).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
    }
}
