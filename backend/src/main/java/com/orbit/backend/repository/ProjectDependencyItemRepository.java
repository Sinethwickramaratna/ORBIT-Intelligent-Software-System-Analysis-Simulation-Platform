package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectDependencyItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectDependencyItemRepository extends JpaRepository<ProjectDependencyItem, UUID> {

    List<ProjectDependencyItem> findByUnitIdInOrderByPositionAsc(Collection<UUID> unitIds);
}
