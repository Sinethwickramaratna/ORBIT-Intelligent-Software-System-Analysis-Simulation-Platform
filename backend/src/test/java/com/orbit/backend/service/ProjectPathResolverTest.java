package com.orbit.backend.service;

import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPathResolverTest {

    private static void assertRejected(ProjectPathResolver resolver, String location, ErrorCode code) {
        assertThatThrownBy(() -> resolver.resolve(location))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // ---- ORBIT in Docker: host folder mounted at /projects ------------------------------------------------------

    private final ProjectPathResolver docker = new ProjectPathResolver("E:/Projects", "/projects");

    @Test
    void windowsPathInsideTheHostRootMapsToTheMount() {
        var r = docker.resolve("E:\\Projects\\shop\\api");
        assertThat(r.path()).isEqualTo(Path.of("/projects/shop/api"));
        assertThat(r.location()).isEqualTo("E:/Projects/shop/api");
    }

    @Test
    void driveLetterAndRootIgnoreCase() {
        var r = docker.resolve("e:/projects/Shop");
        assertThat(r.path()).isEqualTo(Path.of("/projects/Shop"));
        assertThat(r.location()).isEqualTo("E:/Projects/Shop");
    }

    @Test
    void hostRootItselfAndTrailingSeparatorsAreAccepted() {
        assertThat(docker.resolve("E:\\Projects\\").path()).isEqualTo(Path.of("/projects"));
        assertThat(docker.resolve("E:/Projects//shop///").location()).isEqualTo("E:/Projects/shop");
    }

    @Test
    void relativeLocationsGoUnderTheRoot() {
        var r = docker.resolve("my-app");
        assertThat(r.path()).isEqualTo(Path.of("/projects/my-app"));
        assertThat(r.location()).isEqualTo("E:/Projects/my-app");
    }

    @Test
    void containerStylePathIsAcceptedAndStoredAsTheHostPath() {
        var r = docker.resolve("/projects/x");
        assertThat(r.path()).isEqualTo(Path.of("/projects/x"));
        assertThat(r.location()).isEqualTo("E:/Projects/x");
    }

    @Test
    void anythingOutsideTheRootIsRefused() {
        assertRejected(docker, "D:\\Other", ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        assertRejected(docker, "E:/Projectsx/foo", ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        assertRejected(docker, "E:\\Projects\\..\\Secrets", ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        assertRejected(docker, "../escape", ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
        assertRejected(docker, "/etc", ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT);
    }

    @Test
    void errorMessageNamesTheAccessibleFolder() {
        assertThatThrownBy(() -> docker.resolve("D:\\x")).hasMessageContaining("E:/Projects");
        assertThat(docker.visibleRoot()).isEqualTo("E:/Projects");
    }

    @Test
    void withoutAHostRootTheContainerRootIsTheVisibleRoot() {
        var r = new ProjectPathResolver(null, "/projects");
        assertThat(r.visibleRoot()).isEqualTo("/projects");
        assertThat(r.resolve("a/b").location()).isEqualTo("/projects/a/b");
    }

    // ---- ORBIT running on the user's computer -------------------------------------------------------------------

    @Test
    void withoutRootsAnAbsolutePathIsUsedAsIs(@TempDir Path tmp) {
        var host = new ProjectPathResolver(null, null);
        assertThat(host.visibleRoot()).isNull();
        var r = host.resolve(tmp.resolve("app").toString());
        assertThat(r.path()).isEqualTo(tmp.resolve("app"));
    }

    @Test
    void withoutRootsARelativePathIsRefused() {
        assertRejected(new ProjectPathResolver("", ""), "relative/dir", ErrorCode.PROJECT_LOCATION_INVALID);
    }

    // ---- common --------------------------------------------------------------------------------------------------

    @Test
    void blankAndControlCharactersAreRefused() {
        assertRejected(docker, "   ", ErrorCode.PROJECT_LOCATION_INVALID);
        assertRejected(docker, null, ErrorCode.PROJECT_LOCATION_INVALID);
        assertRejected(docker, "E:/Projects/a\u0000b", ErrorCode.PROJECT_LOCATION_INVALID);
    }

    @Test
    void sameLocationComparesWindowsPathsIgnoringCase() {
        assertThat(ProjectPathResolver.sameLocation("E:/Projects/Shop", "e:\\projects\\shop\\")).isTrue();
        assertThat(ProjectPathResolver.sameLocation("/home/a/Shop", "/home/a/shop")).isFalse();
        assertThat(ProjectPathResolver.sameLocation("/home/a/shop", "/home/a/shop/")).isTrue();
    }
}
