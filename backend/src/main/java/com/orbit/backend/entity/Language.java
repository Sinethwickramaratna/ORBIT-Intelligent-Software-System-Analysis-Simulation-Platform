package com.orbit.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** A programming language ORBIT can recognise in a project (Java, Python, ...). */
@Entity
@Table(name = "language")
public class Language {

    @Id
    @Column(name = "language_id", nullable = false, updatable = false)
    private UUID languageId;

    @Column(name = "language_name", nullable = false, unique = true, length = 100)
    private String languageName;

    protected Language() {
    }

    public Language(String languageName) {
        this.languageName = languageName;
    }

    @PrePersist
    void onCreate() {
        if (languageId == null) {
            languageId = UUID.randomUUID();
        }
    }

    public UUID getLanguageId() {
        return languageId;
    }

    public String getLanguageName() {
        return languageName;
    }
}
