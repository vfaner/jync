package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.FanoutProgress;
import com.qqmu.jync.model.ObjectType;

public interface FanoutProgressRepository extends JpaRepository<FanoutProgress, Long> {

    Optional<FanoutProgress> findByProjectIdAndTargetDbIdAndObjectTypeAndObjectName(
            Long projectId, Long targetDbId, ObjectType objectType, String objectName);

    List<FanoutProgress> findByProjectIdAndTargetDbId(Long projectId, Long targetDbId);

    List<FanoutProgress> findByProjectId(Long projectId);

    @Transactional
    void deleteByProjectIdAndTargetDbId(Long projectId, Long targetDbId);

    @Transactional
    void deleteByProjectId(Long projectId);
}
