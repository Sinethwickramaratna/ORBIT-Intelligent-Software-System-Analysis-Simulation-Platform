package com.orbit.backend.deps.version;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Pep440Test {

    private static Pep440.Version v(String s) {
        return Pep440.parse(s).orElseThrow();
    }

    private static boolean ok(String spec, String version) {
        return Pep440.specifier(spec).test(v(version), false);
    }

    @Test
    void versionOrdering() {
        List<String> ordered = List.of("1.0.dev1", "1.0a1", "1.0a2.dev1", "1.0a2", "1.0b1", "1.0rc1", "1.0", "1.0+local",
                "1.0.post1", "1.1", "2!0.1");
        for (int i = 0; i + 1 < ordered.size(); i++) {
            assertThat(v(ordered.get(i))).as(ordered.get(i) + " < " + ordered.get(i + 1)).isLessThan(v(ordered.get(i + 1)));
        }
        assertThat(v("1.0")).isEqualByComparingTo(v("1.0.0"));
        assertThat(v("1.0-alpha-1")).isEqualByComparingTo(v("1.0a1"));
    }

    @Test
    void specifiers() {
        assertThat(ok("~=2.2", "2.9")).isTrue();
        assertThat(ok("~=2.2", "3.0")).isFalse();
        assertThat(ok("~=1.4.5", "1.4.9")).isTrue();
        assertThat(ok("~=1.4.5", "1.5.0")).isFalse();
        assertThat(ok("==1.2.*", "1.2.7")).isTrue();
        assertThat(ok("==1.2.*", "1.3")).isFalse();
        assertThat(ok("!=1.5.*", "1.5.1")).isFalse();
        assertThat(ok(">=2.31,<3", "2.32.3")).isTrue();
        assertThat(ok(">=2.31,<3", "3.0")).isFalse();
        assertThat(ok("==1.0", "1.0+abc")).isTrue();   // local versions match a public ==
        assertThat(ok("<3.0", "3.0rc1")).isFalse();     // pre-release of the bound itself
        assertThat(ok(">1.0", "1.0.post1")).isFalse();  // post-release of the bound itself
        assertThat(ok("", "1.0")).isTrue();
        assertThat(ok("", "2.0b1")).isFalse();          // pre-releases need to be asked for
        assertThat(ok(">=2.0b1", "2.0b2")).isTrue();
        assertThat(Pep440.specifier(">=1").best(List.of("1.0", "1.5", "2.0rc1"))).contains("1.5");
        // a pre-release is taken only when no final release matches (3.0rc1 is lower than 3.0, so >=3 never matches it)
        assertThat(Pep440.specifier(">=2.5").best(List.of("2.0", "3.0rc1"))).contains("3.0rc1");
        assertThat(Pep440.specifier(">=3").best(List.of("2.0", "3.0rc1"))).isEmpty();
    }

    @Test
    void requirementsAndMarkers() {
        Pep440.Requirement r = Pep440.requirement("Requests[security, socks] >=2.0,<3 ; python_version < \"3.13\"").orElseThrow();
        assertThat(r.name()).isEqualTo("Requests");
        assertThat(r.extras()).containsExactlyInAnyOrder("security", "socks");
        assertThat(r.specifier().toString()).isEqualTo(">=2.0,<3");
        Map<String, String> win = Map.of("python_version", "3.12", "sys_platform", "win32", "platform_system", "Windows", "os_name", "nt");
        assertThat(Pep440.marker(r.marker(), win, Set.of())).isTrue();
        assertThat(Pep440.marker("sys_platform == 'linux' or (os_name == 'nt' and python_version >= '3.8')", win, Set.of())).isTrue();
        assertThat(Pep440.marker("sys_platform != \"win32\"", win, Set.of())).isFalse();
        assertThat(Pep440.marker("python_version >= \"3.13\"", win, Set.of())).isFalse();
        assertThat(Pep440.marker("extra == \"socks\"", win, Set.of("socks"))).isTrue();
        assertThat(Pep440.marker("extra == \"socks\"", win, Set.of())).isFalse();
        assertThat(Pep440.marker("'win' in sys_platform", win, Set.of())).isTrue();
        assertThat(Pep440.requirement("pkg @ https://example.com/pkg.whl").orElseThrow().url()).isEqualTo("https://example.com/pkg.whl");
        assertThat(Pep440.normalizeName("Typing_Extensions")).isEqualTo("typing-extensions");
    }
}
