package com.orbit.backend.service;

import com.orbit.backend.dto.response.RepositoryInspectResponse;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Works on local repositories made on the fly, so it needs no network. */
class GitCloneServiceTest {

    private final GitCloneService service = new GitCloneService();

    private static Path makeRepo(Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(dir.resolve("README.md"), "hello");
            Files.createDirectories(dir.resolve("src"));
            Files.writeString(dir.resolve("src/App.java"), "class App {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
            git.branchCreate().setName("develop").call();
            git.checkout().setName("develop").call();
            Files.writeString(dir.resolve("develop.txt"), "dev only");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("dev").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
            git.checkout().setName("main").call();
        }
        return dir;
    }

    private static GitCloneService.RepoRef local(Path p) {
        return new GitCloneService.RepoRef(p.toUri().toString(), "o", "n"); // bypasses parse(): only for tests
    }

    @Test
    void inspectListsBranchesDefaultFirst(@TempDir Path tmp) throws Exception {
        Path origin = makeRepo(tmp.resolve("origin"));
        RepositoryInspectResponse info = service.inspect(local(origin));
        assertThat(info.found()).isTrue();
        assertThat(info.defaultBranch()).isEqualTo("main");
        assertThat(info.branches()).containsExactly("main", "develop");
        assertThat(info.branchCount()).isEqualTo(2);
        assertThat(info.visibility()).isEqualTo("Public");
    }

    @Test
    void inspectOfAMissingRepositoryIsAnError(@TempDir Path tmp) {
        assertThatThrownBy(() -> service.inspect(local(tmp.resolve("missing")))).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.GIT_REPOSITORY_UNAVAILABLE);
    }

    @Test
    void parsesOwnerAndRepositoryName() {
        GitCloneService.RepoRef r = service.parse("  https://github.com/octocat/Hello-World.git ");
        assertThat(r.owner()).isEqualTo("octocat");
        assertThat(r.name()).isEqualTo("Hello-World");
        assertThat(service.parse("https://example.com/team/sub/repo").name()).isEqualTo("repo");
    }

    @Test
    void rejectsNonHttpUrls() {
        for (String bad : new String[]{"", "   ", "file:///etc/passwd", "ssh://git@github.com/a/b.git",
                "git@github.com:a/b.git", "/some/local/path", "C:\\repo", "https://", "https://github.com",
                "https://user:secret@github.com/a/b.git", "ftp://host/a/b"}) {
            assertThatThrownBy(() -> service.parse(bad)).as(bad).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.GIT_URL_INVALID);
        }
    }

    @Test
    void folderNamesAreSafe() {
        assertThat(GitCloneService.folderName("my:repo?")).isEqualTo("my_repo_");
        assertThat(GitCloneService.folderName("..")).isEqualTo("repository");
        assertThat(GitCloneService.folderName("Hello-World")).isEqualTo("Hello-World");
    }

    @Test
    void clonesTheChosenBranchAndEverythingElse(@TempDir Path tmp) throws Exception {
        Path origin = makeRepo(tmp.resolve("origin"));
        Path target = tmp.resolve("work/clone");

        service.cloneRepository(local(origin), "develop", target);

        assertThat(target.resolve("README.md")).exists();
        assertThat(target.resolve("src/App.java")).exists();
        assertThat(target.resolve("develop.txt")).exists(); // develop is checked out
        assertThat(target.resolve(".git")).isDirectory();
        try (Git git = Git.open(target.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo("develop");
        }
    }

    @Test
    void aFailedCloneLeavesNothingBehind(@TempDir Path tmp) {
        Path target = tmp.resolve("work/clone");
        assertThatThrownBy(() -> service.cloneRepository(local(tmp.resolve("missing")), "main", target))
                .isInstanceOf(ApiException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void aFailedCloneIntoAnExistingEmptyFolderKeepsTheFolder(@TempDir Path tmp) throws IOException {
        Path target = Files.createDirectories(tmp.resolve("empty"));
        assertThatThrownBy(() -> service.cloneRepository(local(tmp.resolve("missing")), "main", target))
                .isInstanceOf(ApiException.class);
        assertThat(target).isDirectory();
        try (var entries = Files.list(target)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void anUnknownBranchFailsCleanly(@TempDir Path tmp) throws Exception {
        Path origin = makeRepo(tmp.resolve("origin"));
        Path target = tmp.resolve("clone");
        assertThatThrownBy(() -> service.cloneRepository(local(origin), "nope", target))
                .isInstanceOf(ApiException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void invalidBranchNamesAreRefused(@TempDir Path tmp) {
        assertThatThrownBy(() -> service.cloneRepository(service.parse("https://github.com/a/b.git"), "bad..name", tmp.resolve("x")))
                .isInstanceOf(ApiException.class);
    }
}
