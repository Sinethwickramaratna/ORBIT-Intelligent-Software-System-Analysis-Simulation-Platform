package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectBuildDetail;
import com.orbit.backend.entity.ProjectBuildDetailId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectBuildDetailRepository extends JpaRepository<ProjectBuildDetail, ProjectBuildDetailId> {

    @Query("select d from ProjectBuildDetail d join fetch d.buildSystem where d.id.scanId = :scanId")
    List<ProjectBuildDetail> findByScanIdWithBuildSystem(@Param("scanId") UUID scanId);
}
