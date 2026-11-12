package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

import com.qqmu.jync.util.DurationFormat;

/** An audit record of one change applied to the target database. */
@Entity
@Table(name = "change_log", indexes = {
        @Index(name = "idx_changelog_project", columnList = "project_id"),
        @Index(name = "idx_changelog_time", columnList = "occurred_at")
})
@Getter
@Setter
public class ChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 32)
    private ObjectType objectType;

    @Column(name = "object_name", length = 256)
    private String objectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private ChangeType changeType;

    @Column(name = "affected_rows")
    private Integer affectedRows;

    @Lob
    private String details;

    /**
     * SHA-256 of {@link #details} on the rows that carry a skipped-DDL task; null elsewhere.
     *
     * <p>The DDL-task queries must match a statement, but {@code details} is a LOB and cannot
     * be compared portably: PostgreSQL maps an unbounded {@code @Lob String} to {@code oid},
     * where {@code = ?} silently compares two freshly-created large objects and {@code like}
     * raises {@code operator does not exist: oid ~~ text}. A hash is a plain {@code varchar},
     * so the identity predicate is the same SQL on every metadata store. Maintained by
     * {@code SyncStateWriter#recordSkippedDdl}; rows written before the column existed are
     * filled by {@code ChangeLogDdlHashBackfill} at startup.
     */
    @Column(name = "details_hash", length = 64)
    private String detailsHash;

    /** True when the change was applied successfully. */
    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    // ── DDL 待办任务 ────────────────────────────────────────────────────────────
    // A skipped CDC DDL is not just an audit line: it opens a task an administrator must
    // close (execute the DDL on the target, then replay the gap). These columns stay null
    // on every ordinary audit row, so history and the retention pruner are unaffected.

    public static final String RESOLUTION_PENDING = "PENDING";
    public static final String RESOLUTION_PROCESSED = "PROCESSED";
    public static final String RESOLUTION_FAILED = "FAILED";

    /** Null for ordinary rows; {@link #RESOLUTION_PENDING} until both steps are done. */
    @Column(name = "resolution_status", length = 16)
    private String resolutionStatus;

    @Column(name = "ddl_executed")
    private Boolean ddlExecuted;

    @Column(name = "replay_done")
    private Boolean replayDone;

    /** Editable statement for the target; pre-filled with the source DDL. */
    @Lob
    @Column(name = "target_ddl_sql", length = Integer.MAX_VALUE)
    private String targetDdlSql;

    /** Bounds of the window in which the new column's values were not replicated. */
    @Column(name = "gap_start_file", length = 128)
    private String gapStartFile;
    @Column(name = "gap_start_pos")
    private Long gapStartPos;
    @Column(name = "gap_end_file", length = 128)
    private String gapEndFile;
    @Column(name = "gap_end_pos")
    private Long gapEndPos;

    /** Tables touched during the gap, used by the table-scoped replay. */
    @Lob
    @Column(name = "affected_tables", length = Integer.MAX_VALUE)
    private String affectedTables;

    @Column(name = "handled_by", length = 128)
    private String handledBy;
    @Column(name = "handled_at")
    private Instant handledAt;
    @Lob
    @Column(name = "error_detail", length = Integer.MAX_VALUE)
    private String errorDetail;

    /**
     * The duration as a human label for the change-log table ({@code 99550 ms} → {@code 1m 39s}).
     * Hibernate maps this entity by field, so this getter adds no column.
     */
    public String getDurationLabel() {
        return durationMs == null ? null : DurationFormat.format(durationMs);
    }

    public static ChangeLog of(Long projectId, ObjectType objectType, String objectName,
                              ChangeType changeType, String details) {
        ChangeLog log = new ChangeLog();
        log.projectId = projectId;
        log.objectType = objectType;
        log.objectName = objectName;
        log.changeType = changeType;
        log.details = details;
        log.occurredAt = Instant.now();
        return log;
    }
}
