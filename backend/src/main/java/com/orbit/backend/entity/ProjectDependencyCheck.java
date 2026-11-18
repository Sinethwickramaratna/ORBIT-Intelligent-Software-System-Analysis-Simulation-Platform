package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The newest dependency check of a project (one row per project; a new check replaces the old one). */
@Entity
@Table(name = "project_dependency_check")
public class ProjectDependencyCheck {

    @Id
    @Column(name = "check_id", nullable = false, updatable = false)
    private UUID checkId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private String status;

    @Column(name = "total_count", nullable = false, updatable = false)
    private int totalCount;

    @Column(name = "missing_count", nullable = false, updatable = false)
    private int missingCount;

    @Column(name = "target_platform", nullable = false, updatable = false, length = 50)
    private String targetPlatform;

    protected ProjectDependencyCheck() {
    }

    public ProjectDependencyCheck(UUID projectId, String status, int totalCount, int missingCount, String targetPlatform) {
        this.projectId = projectId;
        this.status = status;
        this.totalCount = totalCount;
        this.missingCount = missingCount;
        this.targetPlatform = targetPlatform;
    }

    @PrePersist
    void onCreate() {
        if (checkId == null) {
            checkId = UUID.randomUUID();
        }
        if (checkedAt == null) {
            checkedAt = Instant.now();
        }
    }

    public UUID getCheckId() {
        return checkId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public String getTargetPlatform() {
        return targetPlatform;
    }
}
