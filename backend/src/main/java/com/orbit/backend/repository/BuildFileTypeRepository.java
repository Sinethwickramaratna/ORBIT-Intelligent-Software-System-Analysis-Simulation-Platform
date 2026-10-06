package com.orbit.backend.repository;

import com.orbit.backend.entity.BuildFileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface BuildFileTypeRepository extends JpaRepository<BuildFileType, UUID> {

    /** Every known evidence file type together with its build system, in a stable order. */
    @Query("select f from BuildFileType f join fetch f.buildSystem b order by b.name, f.fileType")
    List<BuildFileType> findAllWithBuildSystem();
}
