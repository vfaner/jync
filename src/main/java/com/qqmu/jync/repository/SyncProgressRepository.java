package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;

public interface SyncProgressRepository extends JpaRepository<SyncProgress, Long> {

    Optional<SyncProgress> findByProjectIdAndObjectTypeAndObjectName(
            Long projectId, ObjectType objectType, String objectName);

    List<SyncProgress> findByProjectId(Long projectId);

    List<SyncProgress> findByProjectIdAndObjectType(Long projectId, ObjectType objectType);

    /** Tracked-object counts for every project in one query, instead of one query per project. */
    @Query("select p.projectId, count(p) from SyncProgress p "
            + "where p.objectType = :objectType group by p.projectId")
    List<Object[]> countByObjectTypeGroupedByProject(@Param("objectType") ObjectType objectType);

    void deleteByProjectId(Long projectId);
}
