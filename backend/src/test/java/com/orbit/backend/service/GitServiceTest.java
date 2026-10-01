package com.orbit.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitServiceTest {

    private final GitService git = new GitService();

    @Test
    void initCreatesTheRepositorySkeleton(@TempDir Path folder) throws IOException {
        assertThat(git.hasRepository(folder)).isFalse();

        git.init(folder);

        assertThat(git.hasRepository(folder)).isTrue();
        assertThat(Files.readString(folder.resolve(".git/HEAD"))).isEqualTo("ref: refs/heads/main\n");
        assertThat(Files.readString(folder.resolve(".git/config"))).contains("repositoryformatversion = 0");
        assertThat(folder.resolve(".git/objects/pack")).isDirectory();
        assertThat(folder.resolve(".git/refs/heads")).isDirectory();
        assertThat(folder.resolve(".git/refs/tags")).isDirectory();
    }

    @Test
    void initRefusesToTouchAnExistingRepository(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve(".git"));
        Files.writeString(folder.resolve(".git/HEAD"), "ref: refs/heads/trunk\n");

        assertThat(git.hasRepository(folder)).isTrue();
        assertThatThrownBy(() -> git.init(folder)).isInstanceOf(IOException.class);
        assertThat(Files.readString(folder.resolve(".git/HEAD"))).isEqualTo("ref: refs/heads/trunk\n");
    }

    @Test
    void aGitFileCountsAsARepository(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve(".git"), "gitdir: ../elsewhere\n"); // worktree / submodule
        assertThat(git.hasRepository(folder)).isTrue();
    }
}
