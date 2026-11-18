package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectDependencyUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectDependencyUnitRepository extends JpaRepository<ProjectDependencyUnit, UUID> {

    List<ProjectDependencyUnit> findByCheckIdOrderByPositionAsc(UUID checkId);
}
