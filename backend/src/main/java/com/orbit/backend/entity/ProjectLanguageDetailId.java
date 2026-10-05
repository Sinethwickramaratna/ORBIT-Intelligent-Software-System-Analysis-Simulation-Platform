package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link ProjectLanguageDetail}: one row per language per scan. */
@Embeddable
public class ProjectLanguageDetailId implements Serializable {

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "language_id", nullable = false)
    private UUID languageId;

    protected ProjectLanguageDetailId() {
    }

    public ProjectLanguageDetailId(UUID scanId, UUID languageId) {
        this.scanId = scanId;
        this.languageId = languageId;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getLanguageId() {
        return languageId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProjectLanguageDetailId other
                && Objects.equals(scanId, other.scanId) && Objects.equals(languageId, other.languageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, languageId);
    }
}
