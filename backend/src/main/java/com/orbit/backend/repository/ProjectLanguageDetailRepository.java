package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectLanguageDetail;
import com.orbit.backend.entity.ProjectLanguageDetailId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectLanguageDetailRepository extends JpaRepository<ProjectLanguageDetail, ProjectLanguageDetailId> {

    @Query("select d from ProjectLanguageDetail d join fetch d.language where d.id.scanId = :scanId")
    List<ProjectLanguageDetail> findByScanIdWithLanguage(@Param("scanId") UUID scanId);
}
