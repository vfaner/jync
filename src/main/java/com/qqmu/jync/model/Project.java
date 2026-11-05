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

import lombok.Getter;
import lombok.Setter;

/** A source-to-target synchronization project. */
@Entity
@Table(name = "project")
@Getter
@Setter
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(name = "source_db_id")
    private Long sourceDbId;

    @Column(name = "target_db_id")
    private Long targetDbId;

    /**
     * Null on every row written before Redis targets existed; those are JDBC. New rows are
     * written with an explicit value, and the startup backfill fills the old ones in.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", length = 16)
    private SyncTargetKind targetKind;

    /** Set only when {@link #targetKind} is {@code REDIS}. */
    @Column(name = "target_redis_id")
    private Long targetRedisId;

    /** Serialized {@link com.qqmu.jync.dto.SyncConfig}. */
    @Lob
    @Column(name = "sync_config")
    private String syncConfig;

    /** True when the Quartz job for this project should be scheduled. */
    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The kind to branch on. Null means the row predates Redis targets, which is JDBC; every
     * reader must go through this instead of {@link #getTargetKind()} so a legacy row never
     * reaches a null-check that turns into a {@code NullPointerException} or, worse, an
     * accidental Redis branch.
     */
    public SyncTargetKind getTargetKindOrDefault() {
        return targetKind == null ? SyncTargetKind.JDBC : targetKind;
    }

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
