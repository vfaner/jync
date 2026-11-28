package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.FanoutPosition;

public interface FanoutPositionRepository extends JpaRepository<FanoutPosition, Long> {

    Optional<FanoutPosition> findByProjectIdAndTargetDbId(Long projectId, Long targetDbId);

    List<FanoutPosition> findByProjectId(Long projectId);

    @Transactional
    void deleteByProjectIdAndTargetDbId(Long projectId, Long targetDbId);

    @Transactional
    void deleteByProjectId(Long projectId);
}
