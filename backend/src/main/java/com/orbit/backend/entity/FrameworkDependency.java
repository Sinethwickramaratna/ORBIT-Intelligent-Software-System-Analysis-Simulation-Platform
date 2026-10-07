package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A dependency name that proves a framework ("spring-boot-*" in a Maven manifest proves Spring Boot). A trailing
 * {@code *} makes it a prefix rule.
 */
@Entity
@Table(name = "framework_dependency")
public class FrameworkDependency {

    @Id
    @Column(name = "framework_dependency_id", nullable = false, updatable = false)
    private UUID frameworkDependencyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "framework_id", nullable = false)
    private Framework framework;

    @Column(name = "dependency_name", nullable = false, unique = true, length = 255)
    private String dependencyName;

    @Column(name = "package_manager", nullable = false, length = 50)
    private String packageManager;

    protected FrameworkDependency() {
    }

    public FrameworkDependency(Framework framework, String dependencyName, String packageManager) {
        this.framework = framework;
        this.dependencyName = dependencyName;
        this.packageManager = packageManager;
    }

    @PrePersist
    void onCreate() {
        if (frameworkDependencyId == null) {
            frameworkDependencyId = UUID.randomUUID();
        }
    }

    public UUID getFrameworkDependencyId() {
        return frameworkDependencyId;
    }

    public Framework getFramework() {
        return framework;
    }

    public String getDependencyName() {
        return dependencyName;
    }

    public String getPackageManager() {
        return packageManager;
    }
}
