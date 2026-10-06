package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link ProjectBuildEvidence}. */
@Embeddable
public class ProjectBuildEvidenceId implements Serializable {

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "build_system_id", nullable = false)
    private UUID buildSystemId;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    protected ProjectBuildEvidenceId() {
    }

    public ProjectBuildEvidenceId(UUID scanId, UUID buildSystemId, String filePath) {
        this.scanId = scanId;
        this.buildSystemId = buildSystemId;
        this.filePath = filePath;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getBuildSystemId() {
        return buildSystemId;
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProjectBuildEvidenceId other && Objects.equals(scanId, other.scanId)
                && Objects.equals(buildSystemId, other.buildSystemId) && Objects.equals(filePath, other.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, buildSystemId, filePath);
    }
}
