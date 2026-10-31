package com.qqmu.jync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.EtlCheckpoint;

public interface EtlCheckpointRepository extends JpaRepository<EtlCheckpoint, Long> {

    Optional<EtlCheckpoint> findByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
