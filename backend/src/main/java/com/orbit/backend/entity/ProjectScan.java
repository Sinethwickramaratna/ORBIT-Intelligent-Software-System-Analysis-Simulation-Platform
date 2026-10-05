package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One run of the project scan. Every scan is kept, so the history of a project can be looked at later. */
@Entity
@Table(name = "project_scan")
public class ProjectScan {

    @Id
    @Column(name = "scan_id", nullable = false, updatable = false)
    private UUID scanId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "scanned_at", nullable = false, updatable = false)
    private Instant scannedAt;

    protected ProjectScan() {
    }

    public ProjectScan(Project project) {
        this.project = project;
    }

    @PrePersist
    void onCreate() {
        if (scanId == null) {
            scanId = UUID.randomUUID();
        }
        if (scannedAt == null) {
            scannedAt = Instant.now();
        }
    }

    public UUID getScanId() {
        return scanId;
    }

    public Project getProject() {
        return project;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }
}
