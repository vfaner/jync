package com.synctool.model;

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
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-object incremental sync cursor — the durable state that makes restart-safe
 * resumption possible.
 *
 * <p>The cursor is advanced <em>only after</em> the corresponding rows have been
 * committed to the target database. If the tool dies in the window between the target
 * commit and the cursor update, the next run re-reads the same window and replays it;
 * because all data writes are idempotent upserts, the replay is harmless. This gives
 * at-least-once delivery with effectively-once results, which is what is achievable
 * without an XA transaction manager spanning two heterogeneous databases.
 *
 * @see com.synctool.service.sync.SyncEngine
 */
@Entity
@Table(name = "sync_progress",
        uniqueConstraints = @UniqueConstraint(name = "uk_progress_object",
                columnNames = {"project_id", "object_type", "object_name"}),
        indexes = @Index(name = "idx_progress_project", columnList = "project_id"))
@Getter
@Setter
public class SyncProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 32)
    private ObjectType objectType;

    @Column(name = "object_name", nullable = false, length = 256)
    private String objectName;

    /**
     * High-watermark of the last successfully synchronized window: a timestamp in
     * ISO-8601, a numeric id, or a log offset depending on the strategy in use.
     * {@code null} means nothing has been synchronized yet, i.e. the next run is a
     * full initial load.
     */
    @Lob
    @Column(name = "last_sync_value")
    private String lastSyncValue;

    /** Column the cursor is read from, resolved once and cached here. */
    @Column(name = "cursor_column", length = 128)
    private String cursorColumn;

    /** Strategy that produced {@link #lastSyncValue}: TIMESTAMP, IDENTITY, FULL_COMPARE or NONE. */
    @Column(name = "cursor_strategy", length = 32)
    private String cursorStrategy;

    /** True once the initial full load for this object has completed. */
    @Column(name = "initial_load_done")
    private Boolean initialLoadDone = false;

    @Column(name = "rows_synced_total")
    private Long rowsSyncedTotal = 0L;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    @Version
    @Column(name = "opt_version")
    private Long optVersion;
}
