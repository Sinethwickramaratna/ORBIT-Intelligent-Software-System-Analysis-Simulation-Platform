package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * A file extension that belongs to a language ({@code .java} to Java). Keeping the mapping in the database means the
 * scanner needs no hard-coded extension list. Many extensions can belong to one language.
 */
@Entity
@Table(name = "language_extensions",
        uniqueConstraints = @UniqueConstraint(name = "uq_language_extensions_language_extension",
                columnNames = {"language_id", "extension_type"}))
public class LanguageExtension {

    @Id
    @Column(name = "extension_id", nullable = false, updatable = false)
    private UUID extensionId;

    /** Lower-case with its dot, e.g. {@code .java}. */
    @Column(name = "extension_type", nullable = false, length = 50)
    private String extensionType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "language_id", nullable = false)
    private Language language;

    protected LanguageExtension() {
    }

    public LanguageExtension(String extensionType, Language language) {
        this.extensionType = extensionType;
        this.language = language;
    }

    @PrePersist
    void onCreate() {
        if (extensionId == null) {
            extensionId = UUID.randomUUID();
        }
    }

    public UUID getExtensionId() {
        return extensionId;
    }

    public String getExtensionType() {
        return extensionType;
    }

    public Language getLanguage() {
        return language;
    }
}
