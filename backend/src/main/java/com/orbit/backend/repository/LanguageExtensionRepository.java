package com.orbit.backend.repository;

import com.orbit.backend.entity.LanguageExtension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LanguageExtensionRepository extends JpaRepository<LanguageExtension, UUID> {

    /** Every known extension together with its language, in a stable order (language name, then extension). */
    @Query("select e from LanguageExtension e join fetch e.language l order by l.languageName, e.extensionType")
    List<LanguageExtension> findAllWithLanguage();
}
