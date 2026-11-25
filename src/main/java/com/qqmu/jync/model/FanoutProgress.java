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
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-target progress in a fan-out project. The ordinary {@code sync_progress} row describes
 * the project's single target; with N targets each one needs its own first-load state, cursor
 * and bookmark. Keeping the two tables separate means the existing single-target engine and
 * its queries stay untouched.
 */
@Getter
@Setter
@Entity
@Table(name = "fanout_progress",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"project_id", "target_db_id", "object_type", "object_name"}))
public class FanoutProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "target_db_id", nullable = false)
    private Long targetDbId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "object_type", nullable = false, length = 32)
    private ObjectType objectType = ObjectType.TABLE;

    @Column(name = "object_name", nullable = false, length = 256)
    private String objectName;

    @Lob
    @Column(name = "last_sync_value", length = Integer.MAX_VALUE)
    private String lastSyncValue;

    @Column(name = "cursor_column", length = 128)
    private String cursorColumn;

    @Column(name = "cursor_strategy", length = 32)
    private String cursorStrategy;

    @Column(name = "resumable_load")
    private Boolean resumableLoad;

    @Column(name = "initial_load_done")
    private Boolean initialLoadDone = false;

    @Column(name = "full_load_without_cursor")
    private Boolean fullLoadWithoutCursor = false;

    @Column(name = "rows_synced_total")
    private Long rowsSyncedTotal = 0L;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
