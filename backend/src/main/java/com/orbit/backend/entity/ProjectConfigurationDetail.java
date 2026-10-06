package com.orbit.backend.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** One configuration file a scan found, and where (path relative to the project). */
@Entity
@Table(name = "project_configuration_detail")
public class ProjectConfigurationDetail {

    @EmbeddedId
    private ProjectConfigurationDetailId id;

    @MapsId("scanId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private ProjectScan scan;

    @MapsId("configurationFileId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "configuration_file_id", nullable = false)
    private ConfigurationFile configurationFile;

    protected ProjectConfigurationDetail() {
    }

    public ProjectConfigurationDetail(ProjectScan scan, ConfigurationFile configurationFile, String filePath) {
        this.id = new ProjectConfigurationDetailId(scan.getScanId(), configurationFile.getConfigurationFileId(), filePath);
        this.scan = scan;
        this.configurationFile = configurationFile;
    }

    public ProjectConfigurationDetailId getId() {
        return id;
    }

    public ProjectScan getScan() {
        return scan;
    }

    public ConfigurationFile getConfigurationFile() {
        return configurationFile;
    }

    public String getFilePath() {
        return id.getFilePath();
    }
}
