package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;

public interface SyncProgressRepository extends JpaRepository<SyncProgress, Long> {

    Optional<SyncProgress> findByProjectIdAndObjectTypeAndObjectName(
            Long projectId, ObjectType objectType, String objectName);

    List<SyncProgress> findByProjectId(Long projectId);

    List<SyncProgress> findByProjectIdAndObjectType(Long projectId, ObjectType objectType);

    void deleteByProjectId(Long projectId);
}
