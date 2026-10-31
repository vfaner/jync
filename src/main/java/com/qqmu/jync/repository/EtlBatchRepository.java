package com.qqmu.jync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.EtlBatch;

public interface EtlBatchRepository extends JpaRepository<EtlBatch, Long> {

    List<EtlBatch> findAllByOrderByIdDesc();

    List<EtlBatch> findByEnabledTrue();

    long countByEnabledTrue();
}
