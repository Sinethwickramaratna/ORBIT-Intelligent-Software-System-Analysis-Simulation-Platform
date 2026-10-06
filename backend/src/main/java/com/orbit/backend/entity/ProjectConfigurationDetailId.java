package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link ProjectConfigurationDetail}: scan + configuration file + path. */
@Embeddable
public class ProjectConfigurationDetailId implements Serializable {

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "configuration_file_id", nullable = false)
    private UUID configurationFileId;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    protected ProjectConfigurationDetailId() {
    }

    public ProjectConfigurationDetailId(UUID scanId, UUID configurationFileId, String filePath) {
        this.scanId = scanId;
        this.configurationFileId = configurationFileId;
        this.filePath = filePath;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getConfigurationFileId() {
        return configurationFileId;
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProjectConfigurationDetailId other && Objects.equals(scanId, other.scanId)
                && Objects.equals(configurationFileId, other.configurationFileId) && Objects.equals(filePath, other.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scanId, configurationFileId, filePath);
    }
}
