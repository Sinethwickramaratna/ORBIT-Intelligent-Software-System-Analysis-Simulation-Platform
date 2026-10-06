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
 * Evidence for a build system: a file name ({@code pom.xml}) or a {@code *.ext} pattern ({@code *.csproj}) whose
 * presence in a project shows that the project uses that build system.
 */
@Entity
@Table(name = "build_file_type")
public class BuildFileType {

    @Id
    @Column(name = "build_file_id", nullable = false, updatable = false)
    private UUID buildFileId;

    @Column(name = "file_type", nullable = false, unique = true, length = 255)
    private String fileType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "build_system_id", nullable = false)
    private BuildSystem buildSystem;

    protected BuildFileType() {
    }

    public BuildFileType(String fileType, BuildSystem buildSystem) {
        this.fileType = fileType;
        this.buildSystem = buildSystem;
    }

    @PrePersist
    void onCreate() {
        if (buildFileId == null) {
            buildFileId = UUID.randomUUID();
        }
    }

    public UUID getBuildFileId() {
        return buildFileId;
    }

    public String getFileType() {
        return fileType;
    }

    public BuildSystem getBuildSystem() {
        return buildSystem;
    }
}
