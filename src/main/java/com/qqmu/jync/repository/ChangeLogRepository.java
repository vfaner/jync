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
import com.qqmu.jync.model.ChangeType;

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
     * <p>One row per hour, whatever mix of change types the hour holds: the CDC window
     * summaries are {@code INFO} rows and the row changes they describe must land in the same
     * bucket. The dashboard's other line does not come from here — ETL runs have their own
     * history table.
     *
     * <p>Native rather than JPQL: Hibernate cannot resolve the return type of a
     * {@code function(...)} call in the select list, and the timestamp parameter is bound as
     * {@link java.sql.Timestamp} so no driver-side {@code Instant} conversion is involved.
     */
    @Query(value = "select date_trunc('hour', occurred_at), count(*), "
            + "coalesce(sum(affected_rows), 0) "
            + "from change_log where occurred_at >= :since and occurred_at <= :until "
            + "group by date_trunc('hour', occurred_at) "
            + "order by date_trunc('hour', occurred_at)",
            nativeQuery = true)
    List<Object[]> hourlySeriesBetween(@Param("since") java.sql.Timestamp since,
                                       @Param("until") java.sql.Timestamp until);

    /**
     * Per-minute change counts for one project, split by change type.
     *
     * <p>Minute is the finest bucket any page asks for; the caller re-buckets into its range
     * (10 minutes, an hour) in memory. Grouping at minute granularity keeps the row count
     * bounded — at most {@code minutes × 3} rows for a week — so the page never loads a raw
     * change log into memory just to draw a line. Same native {@code date_trunc} precedent as
     * {@link #hourlySeriesBetween}.
     */
    @Query(value = "select date_trunc('minute', occurred_at), change_type, count(*) "
            + "from change_log where project_id = :projectId "
            + "and occurred_at >= :since and occurred_at <= :until "
            + "group by date_trunc('minute', occurred_at), change_type",
            nativeQuery = true)
    List<Object[]> minutelyChangesForProject(@Param("projectId") Long projectId,
                                             @Param("since") java.sql.Timestamp since,
                                             @Param("until") java.sql.Timestamp until);

    /** The hottest tables of one project in the window; the caller caps the page size. */
    @Query(value = "select object_name, count(*) from change_log "
            + "where project_id = :projectId and object_name is not null "
            + "and occurred_at >= :since and occurred_at <= :until "
            + "group by object_name order by count(*) desc",
            nativeQuery = true)
    List<Object[]> topChangedObjects(@Param("projectId") Long projectId,
                                     @Param("since") java.sql.Timestamp since,
                                     @Param("until") java.sql.Timestamp until,
                                     Pageable pageable);

    long countByProjectId(Long projectId);

    /** The alert evaluator's window: failed rows recent enough to describe a live fault. */
    List<ChangeLog> findBySuccessFalseAndOccurredAtAfter(Instant since);

    /**
     * The project's DDL tasks that are still open.
     *
     * <p>Used to tell whether a column is already covered by an open task — a column that
     * an open task adds must not be replayed twice.
     */
    @Query("select c from ChangeLog c where c.projectId = :projectId"
            + " and c.details like 'CDC_SKIPPED_DDL:%'"
            + " and (c.resolutionStatus is null or c.resolutionStatus <> 'PROCESSED')"
            + " order by c.id desc")
    List<ChangeLog> findOpenDdlTasks(@Param("projectId") Long projectId);

    /**
     * How many DDL tasks are still open for the project.
     *
     * <p>An open task means the target may still be missing a column, so the column
     * projection has to stay on across a restart. A count keeps the CLOB details in the
     * database.
     */
    @Query("select count(c) from ChangeLog c where c.projectId = :projectId"
            + " and c.details like 'CDC_SKIPPED_DDL:%'"
            + " and (c.resolutionStatus is null or c.resolutionStatus <> 'PROCESSED')")
    long countOpenDdlTasks(@Param("projectId") Long projectId);

    /**
     * Whether the exact binlog event still has an open task.
     *
     * <p>A consumer restart re-reads a DDL it has already filed; the in-memory dedupe map
     * died with the previous process, so this row is the only surviving record of the
     * event. Position, not statement text, is the identity — a column dropped and added
     * again is a new task even though the ALTER reads the same.
     */
    @Query("select count(c) from ChangeLog c where c.projectId = :projectId"
            + " and c.details like 'CDC_SKIPPED_DDL:%'"
            + " and (c.resolutionStatus is null or c.resolutionStatus <> 'PROCESSED')"
            + " and c.gapStartFile = :file and c.gapStartPos = :pos")
    long countOpenDdlTasksAt(@Param("projectId") Long projectId,
                             @Param("file") String file, @Param("pos") Long pos);

    /** The same check for sources that report no binlog position (LogMiner): text identity. */
    @Query("select count(c) from ChangeLog c where c.projectId = :projectId"
            + " and c.details = :details"
            + " and (c.resolutionStatus is null or c.resolutionStatus <> 'PROCESSED')")
    long countOpenDdlTasksByDetails(@Param("projectId") Long projectId,
                                    @Param("details") String details);

    /** Successful rows of one type, used to spot row-count repairs in the last few minutes. */
    List<ChangeLog> findByChangeTypeAndOccurredAtAfter(ChangeType type, Instant since);

    // Failure-only view of the log: the operator triaging problems wants the error rows
    // without the successful traffic around them.
    Page<ChangeLog> findByProjectIdAndSuccessFalseOrderByOccurredAtDesc(Long projectId,
                                                                        Pageable pageable);

    Page<ChangeLog> findBySuccessFalseOrderByOccurredAtDesc(Pageable pageable);

    long countByProjectIdAndSuccessFalse(Long projectId);

    long countBySuccessFalse();

    /**
     * The dashboard tile's counts, over the very same window the trend chart draws. Both ends
     * are real: a change stamped in the future (a node whose clock runs ahead) must not sit in
     * a "last 24 hours" total that the chart can never show.
     */
    long countByOccurredAtBetween(Instant from, Instant to);

    @Query("select coalesce(sum(c.affectedRows), 0) from ChangeLog c "
            + "where c.occurredAt >= :from and c.occurredAt <= :to")
    long sumAffectedRowsBetween(@Param("from") Instant from, @Param("to") Instant to);

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

    /**
     * The clear button removes history, not work.
     *
     * <p>An open skipped-DDL task is a {@code success = false} row, but deleting it cancels
     * the projection gate and the replay entry point the administrator still needs. The
     * retention pruner already keeps these rows; every clear path does the same. {@code null}
     * details are excluded explicitly: a JPQL {@code not like} on a null is unknown, which
     * would keep those ordinary rows forever.
     */
    String CLEARABLE = " and (c.details is null"
            + " or c.details not like 'CDC_SKIPPED_DDL:%'"
            + " or c.resolutionStatus = 'PROCESSED')";

    /** Remove only the failed entries of one project, in bulk. */
    @Modifying
    @Query("delete from ChangeLog c where c.projectId = :projectId and c.success = false"
            + CLEARABLE)
    int deleteByProjectIdAndSuccessFalse(@Param("projectId") Long projectId);

    /** Remove every failed entry, in bulk. */
    @Modifying
    @Query("delete from ChangeLog c where c.success = false" + CLEARABLE)
    int deleteAllSuccessFalse();

    /** Full clear of one project's log, keeping the DDL tasks that are still open. */
    @Modifying
    @Query("delete from ChangeLog c where c.projectId = :projectId" + CLEARABLE)
    int deleteByProjectIdKeepingOpenDdlTasks(@Param("projectId") Long projectId);

    /** Full clear of every project's log, keeping the DDL tasks that are still open. */
    @Modifying
    @Query("delete from ChangeLog c where 1 = 1" + CLEARABLE)
    int deleteAllBulkKeepingOpenDdlTasks();

    /**
     * Retention pruning for the audit trail.
     *
     * <p>A bulk update/delete query requires a transaction, and the caller (the pruner) must
     * stay outside one so it can swallow failures without hitting a rollback-only commit.
     */
    @Modifying
    @Transactional
    @Query("delete from ChangeLog c where c.occurredAt < :before"
            + " and (c.resolutionStatus is null or c.resolutionStatus = 'PROCESSED')")
    int deleteOlderThan(@Param("before") Instant before);
}
