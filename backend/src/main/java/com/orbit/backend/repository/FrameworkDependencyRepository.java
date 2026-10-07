package com.orbit.backend.repository;

import com.orbit.backend.entity.FrameworkDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface FrameworkDependencyRepository extends JpaRepository<FrameworkDependency, UUID> {

    /** Every known dependency together with its framework, in a stable order. */
    @Query("select d from FrameworkDependency d join fetch d.framework f order by f.name, d.dependencyName")
    List<FrameworkDependency> findAllWithFramework();
}
