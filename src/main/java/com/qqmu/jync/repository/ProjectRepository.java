package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByName(String name);

    List<Project> findByEnabledTrue();

    long countByEnabledTrue();

    List<Project> findBySourceDbIdOrTargetDbId(Long sourceDbId, Long targetDbId);

    List<Project> findBySourceDbId(Long sourceDbId);

    List<Project> findByTargetDbId(Long targetDbId);
}
