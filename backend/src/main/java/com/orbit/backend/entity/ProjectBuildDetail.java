package com.orbit.backend.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A build system that one scan found in a project. */
@Entity
@Table(name = "project_build_detail")
public class ProjectBuildDetail {

    @EmbeddedId
    private ProjectBuildDetailId id;

    @MapsId("scanId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private ProjectScan scan;

    @MapsId("buildSystemId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "build_system_id", nullable = false)
    private BuildSystem buildSystem;

    protected ProjectBuildDetail() {
    }

    public ProjectBuildDetail(ProjectScan scan, BuildSystem buildSystem) {
        this.id = new ProjectBuildDetailId(scan.getScanId(), buildSystem.getBuildSystemId());
        this.scan = scan;
        this.buildSystem = buildSystem;
    }

    public ProjectBuildDetailId getId() {
        return id;
    }

    public ProjectScan getScan() {
        return scan;
    }

    public BuildSystem getBuildSystem() {
        return buildSystem;
    }
}
