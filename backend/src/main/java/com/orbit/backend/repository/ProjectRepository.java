package com.orbit.backend.repository;

import com.orbit.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Project> findByProjectIdAndUser_UserId(UUID projectId, UUID userId);

    boolean existsByUser_UserIdAndLocation(UUID userId, String location);
}
