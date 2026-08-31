package com.synctool.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.synctool.model.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByName(String name);

    boolean existsByName(String name);

    List<Project> findByEnabledTrue();

    long countByEnabledTrue();

    List<Project> findBySourceDbIdOrTargetDbId(Long sourceDbId, Long targetDbId);
}
