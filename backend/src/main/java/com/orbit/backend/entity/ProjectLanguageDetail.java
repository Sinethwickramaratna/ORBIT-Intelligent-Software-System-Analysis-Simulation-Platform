package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** What one scan found for one language: how many files and how many source-code lines. */
@Entity
@Table(name = "project_language_detail")
public class ProjectLanguageDetail {

    @EmbeddedId
    private ProjectLanguageDetailId id;

    @MapsId("scanId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private ProjectScan scan;

    @MapsId("languageId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "language_id", nullable = false)
    private Language language;

    @Column(name = "lines_of_code", nullable = false)
    private int linesOfCode;

    @Column(name = "number_of_files", nullable = false)
    private int numberOfFiles;

    protected ProjectLanguageDetail() {
    }

    public ProjectLanguageDetail(ProjectScan scan, Language language, int linesOfCode, int numberOfFiles) {
        this.id = new ProjectLanguageDetailId(scan.getScanId(), language.getLanguageId());
        this.scan = scan;
        this.language = language;
        this.linesOfCode = linesOfCode;
        this.numberOfFiles = numberOfFiles;
    }

    public ProjectLanguageDetailId getId() {
        return id;
    }

    public ProjectScan getScan() {
        return scan;
    }

    public Language getLanguage() {
        return language;
    }

    public int getLinesOfCode() {
        return linesOfCode;
    }

    public int getNumberOfFiles() {
        return numberOfFiles;
    }
}
