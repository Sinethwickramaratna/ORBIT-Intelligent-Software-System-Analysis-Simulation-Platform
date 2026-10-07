package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectFrameworkEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectFrameworkEvidenceRepository extends JpaRepository<ProjectFrameworkEvidence, UUID> {

    List<ProjectFrameworkEvidence> findByScanId(UUID scanId);
}
