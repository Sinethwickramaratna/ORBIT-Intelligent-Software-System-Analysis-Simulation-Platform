package com.orbit.backend.service;

import com.orbit.backend.dto.response.FolderBrowseResponse;
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

class FolderBrowseServiceTest {

    private static FolderBrowseService mapped(Path mount) {
        return new FolderBrowseService(new ProjectPathResolver("E:/Projects", mount.toString()));
    }

    private static List<String> names(FolderBrowseResponse r) {
        return r.folders().stream().map(FolderBrowseResponse.Folder::name).toList();
    }

    private static void rejected(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void startsAtTheRootListsOnlyVisibleFoldersSortedIgnoringCase(@TempDir Path mount) throws IOException {
        Files.createDirectories(mount.resolve("beta"));
        Files.createDirectories(mount.resolve("Alpha"));
        Files.createDirectories(mount.resolve(".hidden"));
        Files.writeString(mount.resolve("file.txt"), "x");
        FolderBrowseResponse r = mapped(mount).browse(null);
        assertThat(r.path()).isEqualTo("E:/Projects");
        assertThat(r.parent()).isNull();
        assertThat(names(r)).containsExactly("Alpha", "beta");
        assertThat(r.folders().get(0).path()).isEqualTo("E:/Projects/Alpha");
        assertThat(r.shortcuts()).extracting(FolderBrowseResponse.Folder::path).containsExactly("E:/Projects");
    }

    @Test
    void navigatesDownAndHasAParentThatLeadsBack(@TempDir Path mount) throws IOException {
        Files.createDirectories(mount.resolve("a/b"));
        FolderBrowseResponse r = mapped(mount).browse("E:\\Projects\\a");
        assertThat(r.path()).isEqualTo("E:/Projects/a");
        assertThat(r.parent()).isEqualTo("E:/Projects");
        assertThat(names(r)).containsExactly("b");
        assertThat(mapped(mount).browse("E:/Projects/a/b").parent()).isEqualTo("E:/Projects/a");
    }

    @Test
    void cannotLeaveTheRoot(@TempDir Path mount) {
        rejected(() -> mapped(mount).browse("D:/Elsewhere"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        rejected(() -> mapped(mount).browse("E:/Projects/../Other"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        rejected(() -> mapped(mount).browse("E:/Projects/missing"), ErrorCode.PROJECT_LOCATION_NOT_DIRECTORY);
    }

    @Test
    void symlinksPointingOutsideAreNeitherListedNorFollowed(@TempDir Path mount, @TempDir Path outside) throws IOException {
        Path link = mount.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symlinks not available");
        }
        assertThat(names(mapped(mount).browse(null))).doesNotContain("escape");
        rejected(() -> mapped(mount).browse("E:/Projects/escape"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
    }

    @Test
    void newFolderIsCreatedAndRejectsBadNames(@TempDir Path mount) {
        FolderBrowseService s = mapped(mount);
        assertThat(s.createFolder("E:/Projects", " my-app ")).isEqualTo("E:/Projects/my-app");
        assertThat(Files.isDirectory(mount.resolve("my-app"))).isTrue();
        rejected(() -> s.createFolder("E:/Projects", "my-app"), ErrorCode.PROJECT_ALREADY_EXISTS);
        for (String bad : new String[]{"", "..", ".", "a/b", "a\\b", "x:y", "q?", "dot."}) {
            rejected(() -> s.createFolder("E:/Projects", bad), ErrorCode.PROJECT_LOCATION_INVALID);
        }
        rejected(() -> s.createFolder("D:/Elsewhere", "x"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        rejected(() -> s.createFolder("E:/Projects/nope", "x"), ErrorCode.PROJECT_LOCATION_NOT_DIRECTORY);
    }

    @Test
    void hostModeStartsAtHomeAndOffersShortcuts(@TempDir Path dir) throws IOException {
        FolderBrowseService s = new FolderBrowseService(new ProjectPathResolver(null, null));
        Files.createDirectories(dir.resolve("sub"));
        String loc = ProjectPathResolver.tidy(dir.toString());
        FolderBrowseResponse r = s.browse(loc);
        assertThat(r.path()).isEqualTo(loc);
        assertThat(names(r)).containsExactly("sub");
        assertThat(r.parent()).isNotNull();
        FolderBrowseResponse home = s.browse(null);
        assertThat(home.path()).isEqualTo(ProjectPathResolver.tidy(System.getProperty("user.home")));
        assertThat(home.shortcuts()).isNotEmpty();
    }

    private static FolderBrowseService multi(Path e, Path d) {
        return new FolderBrowseService(new ProjectPathResolver(
                "E:/|" + e + ",D:/Work|" + d + ", |/mnt/host/3", null, null));
    }

    @Test
    void severalMountsStartAtAListOfThemAndEachParentLeadsBackToIt(@TempDir Path e, @TempDir Path d) throws IOException {
        Files.createDirectories(e.resolve("games/saves"));
        Files.createDirectories(d.resolve("api"));
        FolderBrowseService s = multi(e, d);

        FolderBrowseResponse top = s.browse(null);
        assertThat(top.path()).isEmpty();
        assertThat(top.parent()).isNull();
        assertThat(names(top)).containsExactly("E:/", "D:/Work");   // unused slot skipped

        FolderBrowseResponse eRoot = s.browse("E:/");
        assertThat(names(eRoot)).containsExactly("games");
        assertThat(eRoot.parent()).isEmpty();                        // "" = back to the list
        FolderBrowseResponse games = s.browse("E:/games");
        assertThat(games.path()).isEqualTo("E:/games");
        assertThat(games.parent()).isEqualTo("E:/");                 // not "E:"
        assertThat(s.browse("E:/games/saves").parent()).isEqualTo("E:/games");
        assertThat(names(s.browse("D:\\Work"))).containsExactly("api");
        assertThat(s.browse("D:/Work/api").parent()).isEqualTo("D:/Work");
    }

    @Test
    void severalMountsStillRefuseEverythingElse(@TempDir Path e, @TempDir Path d) {
        FolderBrowseService s = multi(e, d);
        rejected(() -> s.browse("F:/"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        rejected(() -> s.browse("D:/Other"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        rejected(() -> s.browse("E:/../x"), ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        rejected(() -> s.createFolder("", "x"), ErrorCode.PROJECT_LOCATION_INVALID);
        assertThat(s.createFolder("D:/Work", "new")).isEqualTo("D:/Work/new");
        assertThat(Files.isDirectory(d.resolve("new"))).isTrue();
    }
}
