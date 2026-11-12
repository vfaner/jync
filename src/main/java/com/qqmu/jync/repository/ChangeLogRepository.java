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
     * metadata store the page exists to watch. The bucket is grouped from the calendar fields:
     * HQL's {@code year/month/day/hour} go through the Hibernate dialect, so the query runs on
     * every supported metadata store. A native {@code date_trunc('hour', …)} did not — H2 and
     * PostgreSQL have it, MySQL does not, and a deployment that moved its metadata to MySQL got
     * {@code FUNCTION date_trunc does not exist} on the dashboard and the project performance
     * page. The parameters are {@link Instant}, so Hibernate binds them in the session time zone
     * pinned to UTC in application.yml.
     *
     * <p>Row shape: {@code (year, month, day, hour, count, affectedRows)}. The caller rebuilds
     * the UTC hour boundary with {@code LocalDateTime.of(...).toInstant(UTC)}. Hours with no
     * traffic are absent — the caller fills them, because a chart must not compress a quiet
     * night.
     *
     * <p>One row per hour, whatever mix of change types the hour holds: the CDC window
     * summaries are {@code INFO} rows and the row changes they describe must land in the same
     * bucket. The dashboard's other line does not come from here — ETL runs have their own
     * history table.
     */
    @Query("select year(c.occurredAt), month(c.occurredAt), day(c.occurredAt), "
            + "hour(c.occurredAt), count(c), coalesce(sum(c.affectedRows), 0) "
            + "from ChangeLog c where c.occurredAt >= :since and c.occurredAt <= :until "
            + "group by year(c.occurredAt), month(c.occurredAt), day(c.occurredAt), "
            + "hour(c.occurredAt) "
            + "order by year(c.occurredAt), month(c.occurredAt), day(c.occurredAt), "
            + "hour(c.occurredAt)")
    List<Object[]> hourlySeriesBetween(@Param("since") Instant since,
                                       @Param("until") Instant until);

    /**
     * Per-minute change counts for one project, split by change type.
     *
     * <p>Minute is the finest bucket any page asks for; the caller re-buckets into its range
     * (10 minutes, an hour) in memory. Grouping at minute granularity keeps the row count
     * bounded — at most {@code minutes × 3} rows for a week — so the page never loads a raw
     * change log into memory just to draw a line.
     *
     * <p>Same dialect-portable calendar grouping as {@link #hourlySeriesBetween}; row shape is
     * {@code (year, month, day, hour, minute, changeType, count)}.
     */
    @Query("select year(c.occurredAt), month(c.occurredAt), day(c.occurredAt), "
            + "hour(c.occurredAt), minute(c.occurredAt), c.changeType, count(c) "
            + "from ChangeLog c where c.projectId = :projectId "
            + "and c.occurredAt >= :since and c.occurredAt <= :until "
            + "group by year(c.occurredAt), month(c.occurredAt), day(c.occurredAt), "
            + "hour(c.occurredAt), minute(c.occurredAt), c.changeType")
    List<Object[]> minutelyChangesForProject(@Param("projectId") Long projectId,
                                             @Param("since") Instant since,
                                             @Param("until") Instant until);

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

    /**
     * Skipped-DDL task rows whose {@code details_hash} is still missing — rows written before
     * the column existed. Has no LOB predicate on purpose: the statement is hashed in Java,
     * because on PostgreSQL the {@code details} LOB is an {@code oid} that cannot be
     * compared with text at all.
     */
    List<ChangeLog> findByResolutionStatusIsNotNullAndDetailsHashIsNull();

    /** The alert evaluator's window: failed rows recent enough to describe a live fault. */
    List<ChangeLog> findBySuccessFalseAndOccurredAtAfter(Instant since);

    /**
     * The project's DDL tasks that are still open.
     *
     * <p>Used to tell whether a column is already covered by an open task — a column that
     * an open task adds must not be replayed twice. Only a skipped DDL opens a task, so a
     * non-null {@code resolution_status} is the marker. The predicate deliberately does not
     * look at {@code details}: that is a LOB, and on PostgreSQL ({@code oid}) a {@code like}
     * on it raises {@code operator does not exist: oid ~~ text} — this query runs whenever a
     * CDC consumer starts.
     */
    @Query("select c from ChangeLog c where c.projectId = :projectId"
            + " and c.resolutionStatus is not null"
            + " and c.resolutionStatus <> 'PROCESSED'"
            + " order by c.id desc")
    List<ChangeLog> findOpenDdlTasks(@Param("projectId") Long projectId);

    /**
     * How many DDL tasks are still open for the project.
     *
     * <p>An open task means the target may still be missing a column, so the column
     * projection has to stay on across a restart. A count keeps the LOB details in the
     * database; the open marker is {@code resolution_status}, never the LOB text.
     */
    @Query("select count(c) from ChangeLog c where c.projectId = :projectId"
            + " and c.resolutionStatus is not null"
            + " and c.resolutionStatus <> 'PROCESSED'")
    long countOpenDdlTasks(@Param("projectId") Long projectId);

    /**
     * Whether the exact binlog event was already filed, whatever the task's state.
     *
     * <p>A consumer restart re-reads a DDL it has already filed; the in-memory dedupe map
     * died with the previous process, so this row is the only surviving record of the
     * event. Position <em>and</em> statement are the identity: the position alone is not,
     * because a rebuilt binlog reuses an offset for a different statement. The task's
     * status is deliberately not part of the check — the position only advances on commit,
     * so a dismissed or already-executed task is still the same event and must not be
     * raised again. The statement is matched by its {@code details_hash}, not the LOB.
     */
    @Query("select count(c) from ChangeLog c where c.projectId = :projectId"
            + " and c.detailsHash = :detailsHash"
            + " and c.gapStartFile = :file and c.gapStartPos = :pos")
    long countDdlTasksAt(@Param("projectId") Long projectId,
                         @Param("file") String file, @Param("pos") Long pos,
                         @Param("detailsHash") String detailsHash);

    /** The same check for sources that report no binlog position (LogMiner): text identity. */
    @Query("select count(c) from ChangeLog c where c.projectId = :projectId"
            + " and c.detailsHash = :detailsHash"
            + " and c.resolutionStatus is not null"
            + " and c.resolutionStatus <> 'PROCESSED'")
    long countOpenDdlTasksByHash(@Param("projectId") Long projectId,
                                 @Param("detailsHash") String detailsHash);

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
     * retention pruner already keeps these rows; every clear path does the same, with the
     * very predicate the pruner uses. Only a skipped DDL sets {@code resolution_status}, so
     * that column alone separates a task from history; the {@code details} LOB is not read
     * (PostgreSQL stores it as {@code oid}, where {@code like} raises a type error) and its
     * null handling no longer needs a special case.
     */
    String CLEARABLE = " and (c.resolutionStatus is null or c.resolutionStatus = 'PROCESSED')";

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
