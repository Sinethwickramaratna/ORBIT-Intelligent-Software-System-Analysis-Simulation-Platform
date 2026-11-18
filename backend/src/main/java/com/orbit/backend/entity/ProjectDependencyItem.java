package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** One declared dependency of a {@link ProjectDependencyUnit} and its state at check time. */
@Entity
@Table(name = "project_dependency_item")
public class ProjectDependencyItem {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "dependency_name", nullable = false, updatable = false, length = 500)
    private String dependencyName;

    @Column(name = "required_version", updatable = false, length = 500)
    private String requiredVersion;

    @Column(name = "installed_version", updatable = false, length = 255)
    private String installedVersion;

    @Column(name = "scope", updatable = false, length = 50)
    private String scope;

    @Column(name = "state", nullable = false, updatable = false, length = 20)
    private String state;

    @Column(name = "note", updatable = false, length = 1000)
    private String note;

    protected ProjectDependencyItem() {
    }

    public ProjectDependencyItem(UUID unitId, int position, String dependencyName, String requiredVersion,
                                 String installedVersion, String scope, String state, String note) {
        this.unitId = unitId;
        this.position = position;
        this.dependencyName = dependencyName;
        this.requiredVersion = requiredVersion;
        this.installedVersion = installedVersion;
        this.scope = scope;
        this.state = state;
        this.note = note;
    }

    @PrePersist
    void onCreate() {
        if (itemId == null) {
            itemId = UUID.randomUUID();
        }
    }

    public UUID getUnitId() {
        return unitId;
    }

    public int getPosition() {
        return position;
    }

    public String getDependencyName() {
        return dependencyName;
    }

    public String getRequiredVersion() {
        return requiredVersion;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public String getScope() {
        return scope;
    }

    public String getState() {
        return state;
    }

    public String getNote() {
        return note;
    }
}
