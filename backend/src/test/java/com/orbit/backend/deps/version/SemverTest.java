package com.orbit.backend.deps.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemverTest {

    private static boolean ok(String range, String version) {
        return Semver.range(range).test(version);
    }

    @Test
    void caretRanges() {
        assertThat(ok("^1.2.3", "1.9.0")).isTrue();
        assertThat(ok("^1.2.3", "2.0.0")).isFalse();
        assertThat(ok("^1.2.3", "1.2.2")).isFalse();
        assertThat(ok("^0.2.3", "0.2.9")).isTrue();
        assertThat(ok("^0.2.3", "0.3.0")).isFalse();
        assertThat(ok("^0.0.3", "0.0.4")).isFalse();
        assertThat(ok("^1.2", "1.2.0")).isTrue();
        assertThat(ok("^0.x", "0.9.9")).isTrue();
    }

    @Test
    void tildeXRangesAndComparators() {
        assertThat(ok("~1.2.3", "1.2.9")).isTrue();
        assertThat(ok("~1.2.3", "1.3.0")).isFalse();
        assertThat(ok("~1", "1.9.0")).isTrue();
        assertThat(ok("1.x", "1.4.2")).isTrue();
        assertThat(ok("1.2.*", "1.3.0")).isFalse();
        assertThat(ok("*", "9.9.9")).isTrue();
        assertThat(ok("", "0.0.1")).isTrue();
        assertThat(ok(">=1.0.0 <2.0.0", "1.5.0")).isTrue();
        assertThat(ok(">= 1.0.0 < 2.0.0", "2.0.0")).isFalse();
        assertThat(ok(">1.2", "1.2.9")).isFalse();
        assertThat(ok("<=1.2", "1.2.9")).isTrue();
        assertThat(ok("1.2.3", "1.2.3")).isTrue();
        assertThat(ok("=v1.2.3", "1.2.3")).isTrue();
    }

    @Test
    void hyphenAndOrRanges() {
        assertThat(ok("1.2.3 - 2.3", "2.3.9")).isTrue();
        assertThat(ok("1.2.3 - 2.3", "2.4.0")).isFalse();
        assertThat(ok("1.2.3 - 2.3.4", "2.3.4")).isTrue();
        assertThat(ok("^1.0.0 || ^3.0.0", "3.1.0")).isTrue();
        assertThat(ok("^1.0.0 || ^3.0.0", "2.1.0")).isFalse();
    }

    @Test
    void prereleasesOnlyWhenTheRangeNamesOneOfTheSameVersion() {
        assertThat(ok("^1.0.0", "1.1.0-beta.1")).isFalse();
        assertThat(ok("^1.1.0-beta.0", "1.1.0-beta.2")).isTrue();
        assertThat(ok("^1.1.0-beta.0", "1.2.0-beta.2")).isFalse();
        assertThat(Semver.parse("1.0.0-alpha").orElseThrow()).isLessThan(Semver.parse("1.0.0-alpha.1").orElseThrow());
        assertThat(Semver.parse("1.0.0-2").orElseThrow()).isLessThan(Semver.parse("1.0.0-10").orElseThrow());
        assertThat(Semver.parse("1.0.0-rc.1").orElseThrow()).isLessThan(Semver.parse("1.0.0").orElseThrow());
    }

    @Test
    void maxSatisfyingAndInvalidRanges() {
        assertThat(Semver.range("^4.1.0").maxSatisfying(List.of("4.0.0", "4.1.2", "4.1.10", "5.0.0", "4.2.0-rc.1")))
                .contains("4.1.10");
        assertThat(Semver.range("^9").maxSatisfying(List.of("1.0.0"))).isEmpty();
        assertThatThrownBy(() -> Semver.range("github:user/repo")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Semver.tryRange("latest")).isEmpty();
    }
}
