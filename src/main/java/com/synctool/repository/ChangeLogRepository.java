package com.synctool.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.synctool.model.ChangeLog;

public interface ChangeLogRepository extends JpaRepository<ChangeLog, Long> {

    Page<ChangeLog> findByProjectIdOrderByOccurredAtDesc(Long projectId, Pageable pageable);

    Page<ChangeLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    List<ChangeLog> findTop10ByOrderByOccurredAtDesc();

    List<ChangeLog> findTop20ByProjectIdOrderByOccurredAtDesc(Long projectId);

    long countByOccurredAtAfter(Instant after);

    long countByProjectIdAndSuccessFalse(Long projectId);

    @Query("select coalesce(sum(c.affectedRows), 0) from ChangeLog c where c.occurredAt > :after")
    long sumAffectedRowsSince(@Param("after") Instant after);

    @Query("select coalesce(sum(c.affectedRows), 0) from ChangeLog c where c.projectId = :projectId")
    long sumAffectedRowsByProject(@Param("projectId") Long projectId);

    void deleteByProjectId(Long projectId);

    /** Remove every entry in bulk; returns the number of rows deleted. */
    @Modifying
    @Query("delete from ChangeLog c")
    int deleteAllBulk();

    /** Retention pruning for the audit trail. */
    @Query("delete from ChangeLog c where c.occurredAt < :before")
    @org.springframework.data.jpa.repository.Modifying
    int deleteOlderThan(@Param("before") Instant before);
}
