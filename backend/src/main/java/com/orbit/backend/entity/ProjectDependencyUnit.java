package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** One manifest group of a dependency check (a package.json, a pom.xml module, the Python manifests of a folder ...). */
@Entity
@Table(name = "project_dependency_unit")
public class ProjectDependencyUnit {

    @Id
    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "check_id", nullable = false, updatable = false)
    private UUID checkId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "ecosystem", nullable = false, updatable = false, length = 20)
    private String ecosystem;

    @Column(name = "manifest_path", nullable = false, updatable = false, length = 2000)
    private String manifestPath;

    @Column(name = "install_location", updatable = false, length = 1000)
    private String installLocation;

    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private String status;

    @Column(name = "message", updatable = false, length = 1000)
    private String message;

    protected ProjectDependencyUnit() {
    }

    public ProjectDependencyUnit(UUID checkId, int position, String ecosystem, String manifestPath, String installLocation,
                                 String status, String message) {
        this.unitId = UUID.randomUUID();
        this.checkId = checkId;
        this.position = position;
        this.ecosystem = ecosystem;
        this.manifestPath = manifestPath;
        this.installLocation = installLocation;
        this.status = status;
        this.message = message;
    }

    @PrePersist
    void onCreate() {
        if (unitId == null) {
            unitId = UUID.randomUUID();
        }
    }

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getCheckId() {
        return checkId;
    }

    public int getPosition() {
        return position;
    }

    public String getEcosystem() {
        return ecosystem;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public String getInstallLocation() {
        return installLocation;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
