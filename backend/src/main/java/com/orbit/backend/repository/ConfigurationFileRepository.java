package com.orbit.backend.repository;

import com.orbit.backend.entity.ConfigurationFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConfigurationFileRepository extends JpaRepository<ConfigurationFile, UUID> {

    List<ConfigurationFile> findAllByOrderByFileNameAsc();
}
