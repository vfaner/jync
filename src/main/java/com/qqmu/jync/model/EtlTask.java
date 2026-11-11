package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * One scheduled or manual extraction-and-load job.
 *
 * <p>Deliberately separate from {@link Project}: an ETL job has no polling cursor, no CDC
 * consumer and no structure synchronization, and must never share the real-time scheduler's
 * thread pool. Its source SQL is customer-authored; Jync copies the result rows and does not
 * claim the target matches the source.
 */
@Entity
@Table(name = "etl_task")
@Getter
@Setter
public class EtlTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(name = "source_db_id", nullable = false)
    private Long sourceDbId;

    @Column(name = "target_db_id", nullable = false)
    private Long targetDbId;

    /**
     * Non-null when this task is a member of a batch. The batch owns the source/target
     * connections and the schedule; this row keeps only its own extraction and load settings.
     */
    @Column(name = "batch_id")
    private Long batchId;

    /** Customer-authored query; its result columns become the target column list. */
    @Lob
    @Column(name = "source_sql", nullable = false)
    private String sourceSql;

    /**
     * MongoDB collection read when the source connection uses the native driver. SQL sources
     * leave it empty, and a Mongo task stores an empty {@code sourceSql} so the existing
     * non-null constraint keeps working without a migration.
     */
    @Column(name = "source_collection", length = 255)
    private String sourceCollection;

    /** Optional Extended JSON filter for the native-driver source; blank means everything. */
    @Lob
    @Column(name = "source_filter", length = 4000)
    private String sourceFilter;

    /** Optional SQL executed before extraction: cleanup, deletions, transformations. */
    @Lob
    @Column(name = "pre_sql", length = 2000)
    private String preSql;

    /** Optional SQL executed after load: updates, calculations, post-processing. */
    @Lob
    @Column(name = "post_sql", length = 2000)
    private String postSql;

    /** Plain or schema-qualified table name; the schema defaults to the connection's. */
    @Column(name = "target_table", nullable = false, length = 256)
    private String targetTable;

    @Enumerated(EnumType.STRING)
    @Column(name = "write_mode", nullable = false, length = 24)
    private EtlWriteMode writeMode = EtlWriteMode.OVERWRITE;

    /** Raw SQL predicate for {@link EtlWriteMode#RANGE_OVERWRITE}. */
    @Column(name = "range_condition", length = 1000)
    private String rangeCondition;

    /**
     * Optional unique, monotonic column of the query result. When set, APPEND and
     * RANGE_OVERWRITE resume after the last committed value instead of copying from zero.
     */
    @Column(name = "resume_key_column", length = 128)
    private String resumeKeyColumn;

    /**
     * Comma-separated result columns that identify a row for {@link EtlWriteMode#UPSERT}.
     * A row whose key values already exist on the target updates that row; the rest insert.
     */
    @Column(name = "upsert_key_columns", length = 512)
    private String upsertKeyColumns;

    /** Optional cron expression; blank means manual-only. */
    @Column(name = "cron_expression", length = 128)
    private String cronExpression;

    @Column(nullable = false)
    private Boolean enabled = false;

    /** Optional per-statement query timeout in seconds; null or 0 means no limit. */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "last_run_time")
    private Instant lastRunTime;

    @Column(name = "last_run_success")
    private Boolean lastRunSuccess;

    @Lob
    @Column(name = "last_run_result")
    private String lastRunResult;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "opt_version")
    private Long optVersion;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) {
            this.enabled = false;
        }
        if (this.writeMode == null) {
            this.writeMode = EtlWriteMode.OVERWRITE;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
