package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** A framework ORBIT can recognise in a project (Spring Boot, FastAPI, Express, ...). */
@Entity
@Table(name = "framework")
public class Framework {

    @Id
    @Column(name = "framework_id", nullable = false, updatable = false)
    private UUID frameworkId;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    protected Framework() {
    }

    public Framework(String name) {
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        if (frameworkId == null) {
            frameworkId = UUID.randomUUID();
        }
    }

    public UUID getFrameworkId() {
        return frameworkId;
    }

    public String getName() {
        return name;
    }
}
