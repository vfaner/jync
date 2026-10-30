package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChangeLog;

public interface ChangeLogRepository extends JpaRepository<ChangeLog, Long> {

    Page<ChangeLog> findByProjectIdOrderByOccurredAtDesc(Long projectId, Pageable pageable);

    Page<ChangeLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /** The dashboard feed is a glance, not a report: ten rows; the full list lives at /change-logs. */
    List<ChangeLog> findTop10ByOrderByOccurredAtDesc();

    /**
     * Per-hour totals for the dashboard trend chart, grouped in the database.
     *
     * <p>Bucketing in Java would mean loading a day of traffic into memory first, on the very
     * metadata store the page exists to watch. The store is always H2, whose
     * {@code date_trunc('hour', …)} cuts the bucket; the session time zone is pinned to UTC in
     * application.yml, so the returned timestamp is the UTC hour boundary. Hours with no traffic
     * are absent — the caller fills them, because a chart must not compress a quiet night.
     *
     * <p>Native rather than JPQL: Hibernate cannot resolve the return type of a
     * {@code function(...)} call in the select list, and the timestamp parameter is bound as
     * {@link java.sql.Timestamp} so no driver-side {@code Instant} conversion is involved.
     */
    @Query(value = "select date_trunc('hour', occurred_at), count(*), "
            + "coalesce(sum(affected_rows), 0) "
            + "from change_log where occurred_at >= :since "
            + "group by date_trunc('hour', occurred_at) "
            + "order by date_trunc('hour', occurred_at)",
            nativeQuery = true)
    List<Object[]> hourlySeriesSince(@Param("since") java.sql.Timestamp since);

    long countByProjectId(Long projectId);

    // Failure-only view of the log: the operator triaging problems wants the error rows
    // without the successful traffic around them.
    Page<ChangeLog> findByProjectIdAndSuccessFalseOrderByOccurredAtDesc(Long projectId,
                                                                        Pageable pageable);

    Page<ChangeLog> findBySuccessFalseOrderByOccurredAtDesc(Pageable pageable);

    long countByProjectIdAndSuccessFalse(Long projectId);

    long countBySuccessFalse();

    long countByOccurredAtAfter(Instant after);

    @Query("select coalesce(sum(c.affectedRows), 0) from ChangeLog c where c.occurredAt > :after")
    long sumAffectedRowsSince(@Param("after") Instant after);

    /** Per-project row totals in one query, instead of one query per project on the dashboard. */
    @Query("select c.projectId, coalesce(sum(c.affectedRows), 0) "
            + "from ChangeLog c group by c.projectId")
    List<Object[]> sumAffectedRowsGroupedByProject();

    // Bulk, not a derived delete: a derived delete loads each entity (including the @Lob detail
    // text) before removing it. Project deletion needs only the rows gone, not their contents.
    @Modifying
    @Query("delete from ChangeLog c where c.projectId = :projectId")
    int deleteByProjectId(@Param("projectId") Long projectId);

    /** Remove every entry in bulk; returns the number of rows deleted. */
    @Modifying
    @Query("delete from ChangeLog c")
    int deleteAllBulk();

    /** Remove only the failed entries of one project, in bulk. */
    @Modifying
    @Query("delete from ChangeLog c where c.projectId = :projectId and c.success = false")
    int deleteByProjectIdAndSuccessFalse(@Param("projectId") Long projectId);

    /** Remove every failed entry, in bulk. */
    @Modifying
    @Query("delete from ChangeLog c where c.success = false")
    int deleteAllSuccessFalse();

    /**
     * Retention pruning for the audit trail.
     *
     * <p>A bulk update/delete query requires a transaction, and the caller (the pruner) must
     * stay outside one so it can swallow failures without hitting a rollback-only commit.
     */
    @Modifying
    @Transactional
    @Query("delete from ChangeLog c where c.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
