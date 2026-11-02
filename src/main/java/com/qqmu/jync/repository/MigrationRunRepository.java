package com.qqmu.jync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;

public interface MigrationRunRepository extends JpaRepository<MigrationRun, Long> {

    List<MigrationRun> findTop50ByMigrationTaskIdOrderByStartedAtDesc(Long taskId);

    List<MigrationRun> findByStatus(MigrationRunStatus status);

    long countByMigrationTaskIdAndStatus(Long taskId, MigrationRunStatus status);
}
