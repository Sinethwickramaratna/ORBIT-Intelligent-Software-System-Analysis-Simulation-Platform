package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectDependencyCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProjectDependencyCheckRepository extends JpaRepository<ProjectDependencyCheck, UUID> {

    Optional<ProjectDependencyCheck> findFirstByProjectIdOrderByCheckedAtDesc(UUID projectId);

    /** Removes the older checks of a project (their units and items go with them: ON DELETE CASCADE). */
    @Modifying
    @Query("delete from ProjectDependencyCheck c where c.projectId = :projectId and c.checkId <> :keep")
    int deleteOthers(UUID projectId, UUID keep);
}
