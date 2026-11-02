package com.qqmu.jync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;

public interface MigrationTaskRepository extends JpaRepository<MigrationTask, Long> {

    List<MigrationTask> findByTypeOrderByIdDesc(MigrationType type);

    List<MigrationTask> findByEnabledTrueAndType(MigrationType type);

    long countByType(MigrationType type);

    long countBySourceConnectionIdOrTargetConnectionId(Long source, Long target);

    List<MigrationTask> findBySourceConnectionIdOrTargetConnectionId(Long source, Long target);
}
