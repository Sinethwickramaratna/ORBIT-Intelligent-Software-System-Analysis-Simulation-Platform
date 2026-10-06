package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectBuildEvidence;
import com.orbit.backend.entity.ProjectBuildEvidenceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectBuildEvidenceRepository extends JpaRepository<ProjectBuildEvidence, ProjectBuildEvidenceId> {

    @Query("select e from ProjectBuildEvidence e where e.id.scanId = :scanId")
    List<ProjectBuildEvidence> findByScanId(@Param("scanId") UUID scanId);
}
