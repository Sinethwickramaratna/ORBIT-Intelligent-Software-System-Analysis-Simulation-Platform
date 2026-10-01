package com.orbit.backend.repository;

import com.orbit.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Project> findByProjectIdAndUserId(UUID projectId, UUID userId);

    boolean existsByUserIdAndLocation(UUID userId, String location);
}
