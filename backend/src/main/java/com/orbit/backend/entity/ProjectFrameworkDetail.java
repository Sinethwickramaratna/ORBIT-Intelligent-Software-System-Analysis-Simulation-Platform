package com.orbit.backend.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A framework that one scan found in a project ("SCAN_001 + Spring Boot"). */
@Entity
@Table(name = "project_framework_detail")
public class ProjectFrameworkDetail {

    @EmbeddedId
    private ProjectFrameworkDetailId id;

    @MapsId("scanId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private ProjectScan scan;

    @MapsId("frameworkId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "framework_id", nullable = false)
    private Framework framework;

    protected ProjectFrameworkDetail() {
    }

    public ProjectFrameworkDetail(ProjectScan scan, Framework framework) {
        this.id = new ProjectFrameworkDetailId(scan.getScanId(), framework.getFrameworkId());
        this.scan = scan;
        this.framework = framework;
    }

    public ProjectFrameworkDetailId getId() {
        return id;
    }

    public ProjectScan getScan() {
        return scan;
    }

    public Framework getFramework() {
        return framework;
    }
}
