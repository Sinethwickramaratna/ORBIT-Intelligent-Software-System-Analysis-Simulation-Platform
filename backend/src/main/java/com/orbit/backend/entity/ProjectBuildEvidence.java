package com.orbit.backend.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One file (path relative to the project) that proved a build system in a scan. */
@Entity
@Table(name = "project_build_evidence")
public class ProjectBuildEvidence {

    @EmbeddedId
    private ProjectBuildEvidenceId id;

    protected ProjectBuildEvidence() {
    }

    public ProjectBuildEvidence(java.util.UUID scanId, java.util.UUID buildSystemId, String filePath) {
        this.id = new ProjectBuildEvidenceId(scanId, buildSystemId, filePath);
    }

    public ProjectBuildEvidenceId getId() {
        return id;
    }
}
