package com.synctool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Externalized tuning knobs, bound from the {@code sync.*} prefix. */
@ConfigurationProperties(prefix = "sync")
@Getter
@Setter
public class SyncProperties {

    /** Default polling interval in milliseconds. */
    private long pollInterval = 2000L;

    private String snapshotDir = "./snapshots";

    private int maxRetries = 3;

    private int batchSize = 500;

    private int fetchSize = 1000;

    /**
     * Slack subtracted from the timestamp high-watermark before it is persisted.
     *
     * <p>A row's timestamp is assigned when the statement runs, but the row only becomes
     * visible to us at commit. A transaction that started before our read but commits
     * after it would otherwise be permanently skipped, since its timestamp is below the
     * watermark we saved. Rewinding the watermark by this margin makes such rows be
     * re-read on the following poll. The overlap re-delivers a few already-synced rows,
     * which the idempotent upsert absorbs harmlessly.
     */
    private long safetyLagMs = 1000L;

    /** Tables without a usable cursor column are full-compared only below this row count. */
    private long fullCompareMaxRows = 20000L;

    /** Lifetime of a per-project sync lock, after which a crashed owner's lock is stealable. */
    private long lockTtlMs = 300_000L;

    /**
     * Shortest gap between two row-count audits of the same table; 0 disables the audit.
     *
     * <p>The audit verifies that every source row the cursor claims to have delivered is
     * actually present in the target, and it costs one {@code COUNT(*)} per side. On InnoDB
     * that is a full index scan, so it must not run on every poll of a two-second cycle.
     */
    private long rowCountAuditIntervalMs = 60_000L;

    private String cryptoPassword = "synctool-default-key-change-me";

    private String cryptoSalt = "5c0744940b5c369b";

    /** Days of change-log history to keep; 0 disables pruning. */
    private int changeLogRetentionDays = 30;
}
