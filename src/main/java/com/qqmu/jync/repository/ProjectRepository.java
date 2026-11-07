package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.qqmu.jync.model.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** The two columns the Prometheus label map needs; the entity carries a large @Lob. */
    interface IdAndName {
        Long getId();

        String getName();
    }

    Optional<Project> findByName(String name);

    @Query("select p.id as id, p.name as name from Project p")
    List<IdAndName> findAllIdAndName();

    List<Project> findByEnabledTrue();

    long countByEnabledTrue();

    List<Project> findBySourceDbIdOrTargetDbId(Long sourceDbId, Long targetDbId);

    List<Project> findBySourceDbId(Long sourceDbId);

    List<Project> findByTargetDbId(Long targetDbId);

    boolean existsByTargetRedisId(Long targetRedisId);

    List<Project> findByTargetRedisId(Long targetRedisId);
}
