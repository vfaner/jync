package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * One dedicated-migration job: a source connection, a target connection and type-specific
 * options. It runs manually or on its own cron, never through the real-time scheduler or ETL.
 */
@Entity
@Table(name = "migration_task")
@Getter
@Setter
public class MigrationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MigrationType type;

    @Column(name = "source_connection_id", nullable = false)
    private Long sourceConnectionId;

    @Column(name = "target_connection_id", nullable = false)
    private Long targetConnectionId;

    /** The handler owns this shape; the generic module only stores and displays it. */
    @Lob
    @Column(name = "options_json", nullable = false)
    private String optionsJson;

    /** Optional cron expression; blank means manual-only. */
    @Column(name = "cron_expression", length = 128)
    private String cronExpression;

    @Column(nullable = false)
    private Boolean enabled = false;

    /** Per-run wall-clock limit in seconds; null or 0 means no limit. */
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
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
