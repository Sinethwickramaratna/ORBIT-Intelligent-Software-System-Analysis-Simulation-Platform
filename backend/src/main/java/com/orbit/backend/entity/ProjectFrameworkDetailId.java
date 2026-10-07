package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link ProjectFrameworkDetail}: one row per framework per scan. */
@Embeddable
public class ProjectFrameworkDetailId implements Serializable {

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "framework_id", nullable = false)
    private UUID frameworkId;

    protected ProjectFrameworkDetailId() {
    }

    public ProjectFrameworkDetailId(UUID scanId, UUID frameworkId) {
        this.scanId = scanId;
        this.frameworkId = frameworkId;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getFrameworkId() {
        return frameworkId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProjectFrameworkDetailId other
                && Objects.equals(scanId, other.scanId) && Objects.equals(frameworkId, other.frameworkId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, frameworkId);
    }
}
