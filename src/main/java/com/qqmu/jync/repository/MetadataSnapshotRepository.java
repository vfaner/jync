package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.MetadataSnapshot;
import com.qqmu.jync.model.ObjectType;

public interface MetadataSnapshotRepository extends JpaRepository<MetadataSnapshot, Long> {

    Optional<MetadataSnapshot> findByProjectIdAndObjectTypeAndObjectName(
            Long projectId, ObjectType objectType, String objectName);

    List<MetadataSnapshot> findByProjectId(Long projectId);

    List<MetadataSnapshot> findByProjectIdAndObjectType(Long projectId, ObjectType objectType);

    void deleteByProjectId(Long projectId);

    void deleteByProjectIdAndObjectTypeAndObjectName(Long projectId, ObjectType objectType, String objectName);
}
