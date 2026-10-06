package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link ProjectBuildDetail}: one row per build system per scan. */
@Embeddable
public class ProjectBuildDetailId implements Serializable {

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "build_system_id", nullable = false)
    private UUID buildSystemId;

    protected ProjectBuildDetailId() {
    }

    public ProjectBuildDetailId(UUID scanId, UUID buildSystemId) {
        this.scanId = scanId;
        this.buildSystemId = buildSystemId;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getBuildSystemId() {
        return buildSystemId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProjectBuildDetailId other
                && Objects.equals(scanId, other.scanId) && Objects.equals(buildSystemId, other.buildSystemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, buildSystemId);
    }
}
