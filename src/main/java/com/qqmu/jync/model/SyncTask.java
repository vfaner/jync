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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/** The scheduled sync task backing a project. One row per project. */
@Entity
@Table(name = "sync_task")
@Getter
@Setter
public class SyncTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    // No cron column here: the schedule (cron or poll interval) lives in the project's sync
    // config. A legacy sync_task.cron_expression field existed but was never written by any
    // version — anything reading it would always see null.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.STOPPED;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    /**
     * When a cycle last finished without error — the heartbeat behind the delay card.
     * {@link #lastSyncTime} stands still on an idle source by design, so it cannot answer
     * "is the engine still turning"; this one moves on every clean pass.
     */
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Lob
    @Column(name = "last_sync_result")
    private String lastSyncResult;

    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures = 0;

    /**
     * Held by the node currently executing this task, together with
     * {@link #lockExpiresAt}. Guards against concurrent execution across
     * restarts and across nodes when the metadata store is shared.
     */
    @Column(name = "lock_owner", length = 128)
    private String lockOwner;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    /** Optimistic lock, so two nodes cannot both win the sync lock. */
    @Version
    @Column(name = "opt_version")
    private Long optVersion;
}
