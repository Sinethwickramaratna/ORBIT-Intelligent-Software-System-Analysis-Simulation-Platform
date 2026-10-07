package com.orbit.backend.repository;

import com.orbit.backend.entity.ProjectFrameworkDetail;
import com.orbit.backend.entity.ProjectFrameworkDetailId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectFrameworkDetailRepository extends JpaRepository<ProjectFrameworkDetail, ProjectFrameworkDetailId> {

    @Query("select d from ProjectFrameworkDetail d join fetch d.framework where d.id.scanId = :scanId")
    List<ProjectFrameworkDetail> findByScanIdWithFramework(@Param("scanId") UUID scanId);
}
