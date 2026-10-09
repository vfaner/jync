package com.qqmu.jync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.qqmu.jync.service.metadata.MetadataTimeouts;

import lombok.Getter;
import lombok.Setter;

/** Externalized tuning knobs, bound from the {@code sync.*} prefix. */
@ConfigurationProperties(prefix = "sync")
@Getter
@Setter
public class SyncProperties {

    /** Default polling interval in milliseconds. */
    private long pollInterval = 2000L;

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

    /**
     * Maximum size of each per-database connection pool.
     *
     * <p>A running cycle borrows only one connection per side, but the pool must also absorb
     * the on-demand "sync now", the admin UI and concurrent test connections. A hard-coded
     * value of 10 per database multiplied across many configured databases could exhaust a
     * database server's connection limit, hence the externalized, smaller default.
     */
    private int maxPoolSize = 5;

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

    /**
     * How long a data-plane statement may run before the driver aborts it.
     *
     * <p>Without a bound, a locked table or a broken network stack parks the sync worker (and the
     * Quartz thread it runs on) indefinitely. 0 disables the timeout for drivers that do not
     * support it.
     */
    private int queryTimeoutSeconds = 300;

    /** Same bound for short metadata enumeration queries, which have no reason to run for minutes. */
    private int metadataQueryTimeoutSeconds = 60;

    /**
     * Upper bound on waiting for in-flight sync cycles during application shutdown.
     *
     * <p>Shutdown gives running cycles this long to finish; cycles still executing afterwards
     * are abandoned (the scheduler shuts without waiting, and the database rolls back any
     * open batch). 0 skips the wait. The bound exists because waiting unconditionally makes a
     * stuck cycle (up to the data-plane timeout, or a wedged driver) hang process shutdown.
     */
    private long shutdownWaitMs = 20_000L;

    /** Built-in crypto password. Deliberately still the pre-rename (SyncTool) literal — see the NOTE in application.yml. */
    public static final String DEFAULT_CRYPTO_PASSWORD = "synctool-default-key-change-me";

    public static final String DEFAULT_CRYPTO_SALT = "5c0744940b5c369b";

    private String cryptoPassword = DEFAULT_CRYPTO_PASSWORD;

    private String cryptoSalt = DEFAULT_CRYPTO_SALT;

    /** Days of change-log history to keep; 0 disables pruning. */
    private int changeLogRetentionDays = 30;

    /** Pushes the configured metadata timeout to readers once binding is done. */
    @javax.annotation.PostConstruct
    void configureMetadataTimeouts() {
        MetadataTimeouts.configure(metadataQueryTimeoutSeconds);
    }

    private final Ai ai = new Ai();

    /**
     * AI-assisted conversion settings.
     *
     * <p>This is the only part of the tool that can talk to anything outside the two databases.
     * It stays inert until a provider is configured and enabled, so a stock install still makes
     * no outbound request. Setting {@code sync.ai.enabled=false} removes the feature entirely --
     * menu and endpoints -- which is the clean way to lock down an air-gapped deployment.
     */
    @Getter
    @Setter
    public static class Ai {
        private boolean enabled = true;
    }
}
