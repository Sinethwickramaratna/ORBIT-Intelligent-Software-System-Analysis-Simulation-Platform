package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectScan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectScanRepository extends JpaRepository<ProjectScan, UUID> {

    /** The most recent scan of a project. */
    Optional<ProjectScan> findFirstByProject_ProjectIdOrderByScannedAtDesc(UUID projectId);
}
