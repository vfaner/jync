package com.synctool.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Version;

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

    @Column(name = "task_name", length = 128)
    private String taskName;

    /** Optional cron expression; when blank the fixed poll interval is used. */
    @Column(name = "cron_expression", length = 128)
    private String cronExpression;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.STOPPED;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

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
