package com.orbit.backend.dto.response;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The result of one project scan as the UI shows it. {@code languages} is sorted by source-code lines, largest
 * first; {@code percentage} is that language's share of all source-code lines (one decimal).
 */
public record ProjectScanResponse(
        UUID scanId,
        UUID projectId,
        Instant scannedAt,
        int totalFiles,
        long totalLines,
        List<LanguageShare> languages) {

    /** What was counted for one language (the input of {@link #of}). */
    public record Counts(String language, int files, int lines) {
    }

    public record LanguageShare(String language, int files, int lines, double percentage) {
    }

    private static final Comparator<Counts> LARGEST_FIRST = Comparator
            .comparingInt(Counts::lines).reversed()
            .thenComparing(Comparator.comparingInt(Counts::files).reversed())
            .thenComparing(c -> c.language().toLowerCase(Locale.ROOT));

    public static ProjectScanResponse of(UUID scanId, UUID projectId, Instant scannedAt, Collection<Counts> counts) {
        long totalLines = 0;
        int totalFiles = 0;
        for (Counts c : counts) {
            totalLines += c.lines();
            totalFiles += c.files();
        }
        final long total = totalLines;
        List<LanguageShare> languages = counts.stream()
                .sorted(LARGEST_FIRST)
                .map(c -> new LanguageShare(c.language(), c.files(), c.lines(), percentage(c.lines(), total)))
                .toList();
        return new ProjectScanResponse(scanId, projectId, scannedAt, totalFiles, totalLines, languages);
    }

    /** {@code part / total} as a percentage rounded to one decimal; 0 when nothing was counted. */
    static double percentage(long part, long total) {
        return total <= 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }
}
