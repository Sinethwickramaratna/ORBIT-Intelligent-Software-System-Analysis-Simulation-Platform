package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** One dependency declaration that proved a framework: manifest file, line and column (both 1-based). */
@Entity
@Table(name = "project_framework_evidence")
public class ProjectFrameworkEvidence {

    @Id
    @Column(name = "evidence_id", nullable = false, updatable = false)
    private UUID evidenceId;

    @Column(name = "scan_id", nullable = false, updatable = false)
    private UUID scanId;

    @Column(name = "framework_id", nullable = false, updatable = false)
    private UUID frameworkId;

    @Column(name = "dependency_name", nullable = false, updatable = false, length = 255)
    private String dependencyName;

    @Column(name = "file_path", nullable = false, updatable = false, length = 1000)
    private String filePath;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Column(name = "col_number", nullable = false, updatable = false)
    private int colNumber;

    protected ProjectFrameworkEvidence() {
    }

    public ProjectFrameworkEvidence(UUID scanId, UUID frameworkId, String dependencyName, String filePath,
                                    int lineNumber, int colNumber) {
        this.scanId = scanId;
        this.frameworkId = frameworkId;
        this.dependencyName = dependencyName;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.colNumber = colNumber;
    }

    @PrePersist
    void onCreate() {
        if (evidenceId == null) {
            evidenceId = UUID.randomUUID();
        }
    }

    public UUID getEvidenceId() {
        return evidenceId;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getFrameworkId() {
        return frameworkId;
    }

    public String getDependencyName() {
        return dependencyName;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getColNumber() {
        return colNumber;
    }
}
