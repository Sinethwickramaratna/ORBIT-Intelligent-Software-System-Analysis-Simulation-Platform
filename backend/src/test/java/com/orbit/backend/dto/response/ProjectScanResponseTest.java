package com.orbit.backend.dto.response;

import com.orbit.backend.dto.response.ProjectScanResponse.Counts;
import com.orbit.backend.dto.response.ProjectScanResponse.LanguageShare;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectScanResponseTest {

    private static ProjectScanResponse of(List<Counts> counts) {
        return ProjectScanResponse.of(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), counts);
    }

    @Test
    void percentagesAreSharesOfAllLinesSortedLargestFirst() {
        ProjectScanResponse r = of(List.of(new Counts("Python", 1, 700), new Counts("Java", 3, 7800), new Counts("TypeScript", 2, 1500)));

        assertThat(r.languages()).extracting(LanguageShare::language).containsExactly("Java", "TypeScript", "Python");
        assertThat(r.languages()).extracting(LanguageShare::percentage).containsExactly(78.0, 15.0, 7.0);
        assertThat(r.totalLines()).isEqualTo(10000);
        assertThat(r.totalFiles()).isEqualTo(6);
    }

    @Test
    void emptyAndZeroLineScansHaveNoDivisionByZero() {
        assertThat(of(List.of()).languages()).isEmpty();
        assertThat(of(List.of(new Counts("Java", 3, 0))).languages().get(0).percentage()).isZero();
    }

    @Test
    void equalLinesAreOrderedByFilesThenName() {
        ProjectScanResponse r = of(List.of(new Counts("b", 1, 10), new Counts("a", 1, 10), new Counts("c", 5, 10)));

        assertThat(r.languages()).extracting(LanguageShare::language).containsExactly("c", "a", "b");
    }
}
