package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** A build system or dependency manager ORBIT can recognise in a project (Maven, Gradle, npm, ...). */
@Entity
@Table(name = "build_system")
public class BuildSystem {

    @Id
    @Column(name = "build_system_id", nullable = false, updatable = false)
    private UUID buildSystemId;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    protected BuildSystem() {
    }

    public BuildSystem(String name) {
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        if (buildSystemId == null) {
            buildSystemId = UUID.randomUUID();
        }
    }

    public UUID getBuildSystemId() {
        return buildSystemId;
    }

    public String getName() {
        return name;
    }
}
