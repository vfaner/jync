package com.qqmu.jync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.EtlTask;

public interface EtlTaskRepository extends JpaRepository<EtlTask, Long> {

    List<EtlTask> findAllByOrderByIdDesc();

    List<EtlTask> findByEnabledTrue();
}
