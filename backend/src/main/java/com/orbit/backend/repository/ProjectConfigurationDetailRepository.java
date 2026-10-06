package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectConfigurationDetail;
import com.orbit.backend.entity.ProjectConfigurationDetailId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectConfigurationDetailRepository extends JpaRepository<ProjectConfigurationDetail, ProjectConfigurationDetailId> {

    @Query("select d from ProjectConfigurationDetail d join fetch d.configurationFile where d.id.scanId = :scanId")
    List<ProjectConfigurationDetail> findByScanIdWithFile(@Param("scanId") UUID scanId);
}
