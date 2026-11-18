package com.orbit.backend.deps.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NuGetVersionTest {

    @Test
    void rangesPickTheLowestApplicableVersion() {
        List<String> versions = List.of("12.0.1", "13.0.1", "13.0.3", "14.0.0-beta1");
        assertThat(NuGetVersion.range("13.0.1").orElseThrow().pick(versions)).contains("13.0.1");
        assertThat(NuGetVersion.range("13.0.2").orElseThrow().pick(versions)).contains("13.0.3");
        assertThat(NuGetVersion.range("[12.0,13.0)").orElseThrow().pick(versions)).contains("12.0.1");
        assertThat(NuGetVersion.range("[13.0.3]").orElseThrow().pick(versions)).contains("13.0.3");
        assertThat(NuGetVersion.range("(,13.0.1]").orElseThrow().test("13.0.3")).isFalse();
    }

    @Test
    void floatingVersionsPickTheHighest() {
        List<String> versions = List.of("6.0.0", "6.0.1", "7.0.0");
        assertThat(NuGetVersion.range("6.*").orElseThrow().pick(versions)).contains("6.0.1");
        assertThat(NuGetVersion.range("*").orElseThrow().pick(versions)).contains("7.0.0");
    }

    @Test
    void normalizedFolderNames() {
        assertThat(NuGetVersion.parse("1.0").orElseThrow().normalized()).isEqualTo("1.0.0");
        assertThat(NuGetVersion.parse("1.2.3.0").orElseThrow().normalized()).isEqualTo("1.2.3");
        assertThat(NuGetVersion.parse("1.2.3.4-Beta").orElseThrow().normalized()).isEqualTo("1.2.3.4-beta");
        assertThat(NuGetVersion.range("not a version")).isEmpty();
    }
}
