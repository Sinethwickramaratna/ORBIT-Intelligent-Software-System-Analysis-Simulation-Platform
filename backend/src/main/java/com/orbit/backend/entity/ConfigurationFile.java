package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** A configuration file ORBIT looks for in a project, by file name (application.yml, Dockerfile, ...). */
@Entity
@Table(name = "configuration_file")
public class ConfigurationFile {

    @Id
    @Column(name = "configuration_file_id", nullable = false, updatable = false)
    private UUID configurationFileId;

    @Column(name = "file_name", nullable = false, unique = true, length = 255)
    private String fileName;

    protected ConfigurationFile() {
    }

    public ConfigurationFile(String fileName) {
        this.fileName = fileName;
    }

    @PrePersist
    void onCreate() {
        if (configurationFileId == null) {
            configurationFileId = UUID.randomUUID();
        }
    }

    public UUID getConfigurationFileId() {
        return configurationFileId;
    }

    public String getFileName() {
        return fileName;
    }
}
