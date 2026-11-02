package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * One dedicated-migration attempt.
 *
 * <p>Kept after its task is deleted, like {@link EtlRun}: the history is evidence of what was
 * copied. A run left {@code RUNNING} by a crash is marked {@code INTERRUPTED} at startup.
 */
@Entity
@Table(name = "migration_run", indexes = {
        @Index(name = "idx_migration_run_task_time",
                columnList = "migration_task_id, started_at")
})
@Getter
@Setter
public class MigrationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable on purpose: deleting a task must not delete the history it left behind. */
    @Column(name = "migration_task_id")
    private Long migrationTaskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private MigrationRunTrigger triggerType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "indices_total", nullable = false)
    private int indicesTotal;

    @Column(name = "indices_succeeded", nullable = false)
    private int indicesSucceeded;

    @Column(name = "indices_failed", nullable = false)
    private int indicesFailed;

    @Column(name = "documents_read", nullable = false)
    private long documentsRead;

    @Column(name = "documents_written", nullable = false)
    private long documentsWritten;

    @Column(name = "current_index", length = 255)
    private String currentIndex;

    @Lob
    private String message;
}
