package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A group of ETL tasks that share one source connection, one target connection and one cron.
 *
 * <p>Each member keeps its own extraction SQL, target table, write mode, resume key and
 * checkpoint; the batch exists so a database with fifty tables needs one schedule and one pair
 * of connection pools instead of fifty of each. Members run one after another inside a single
 * executor slot.
 */
@Entity
@Table(name = "etl_batch")
@Getter
@Setter
public class EtlBatch {

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

    /** Cron for the whole group; members carry no schedule of their own. */
    @Column(name = "cron_expression", length = 128)
    private String cronExpression;

    @Column(nullable = false)
    private Boolean enabled = false;

    /** Default for members created from the table list; each member may override it. */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "last_run_time")
    private Instant lastRunTime;

    @Column(name = "last_run_success")
    private Boolean lastRunSuccess;

    @Lob
    @Column(name = "last_run_result", length = Integer.MAX_VALUE)
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
