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
     * Rows per keyset chunk of an initial full load; 0 disables chunking.
     *
     * <p>Chunking turns one table-long streamed read into a chain of short bounded reads, each
     * committed and checkpointed on its own, so a crash mid-table costs at most one chunk of
     * re-read work instead of the whole table. Only tables with a primary key can be chunked;
     * keyless tables always keep the single streamed read.
     */
    private int chunkSize = 100_000;

    /**
     * Worker threads for one chunked initial full load; 1 keeps the load fully sequential.
     *
     * <p>Parallel workers split the primary-key span into disjoint ranges (MIN/MAX bounds)
     * and copy them concurrently; requires a single-column integer PK, other tables stay
     * sequential. Each worker borrows its own source/target connection per chunk, so the
     * effective concurrency is also bounded by {@link #maxPoolSize} — parallelism beyond the
     * pool size simply queues on it. Values above 8 are clamped.
     */
    private int fullLoadParallelism = 1;

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

    /** MySQL binlog CDC settings (v1.5.0, task book 阶段 B). Bound from {@code sync.cdc.*}. */
    private final Cdc cdc = new Cdc();

    @Getter
    @Setter
    public static class Cdc {
        /** Initial reconnect delay after a dropped binlog stream; doubles up to the max. */
        private long reconnectBackoffMs = 1_000L;
        /** Cap for the exponential reconnect backoff (task book B7: 1s → 60s). */
        private long reconnectBackoffMaxMs = 60_000L;
        /**
         * Base for the fake replica server id this tool presents to the source.
         * Effective id = serverIdOffset + projectId, so parallel projects never collide.
         */
        private long serverIdOffset = 612_000L;
        /**
         * Lease TTL for the long-running CDC consumer. Deliberately much shorter than the
         * polling lock TTL: a crashed node's lease expires within this window so another
         * instance can take over inside the 60s acceptance bound.
         */
        private long lockTtlMs = 30_000L;
        /** How often the consumer supervisor retries lock acquisition for wanted projects. */
        private long superviseIntervalMs = 10_000L;
        /**
         * Age at which a per-table CDC change window is flushed as one aggregated change-log
         * entry. CDC is a continuous stream, so per-row logging would flood the log; counts
         * per table land at most this often, with a final flush when the consumer stops.
         * Set to 0 to disable aggregated change logging.
         */
        private long changeWindowMs = 10_000L;
        /** Handshake timeout for BinaryLogClient.connect(). */
        private long connectTimeoutMs = 10_000L;

        /** Oracle LogMiner tuning (v1.6.0) — only consulted when a project runs LOGMINER_CDC. */
        private final Logminer logminer = new Logminer();

        /** PostgreSQL logical slot tuning (v1.7.0) — only consulted when a project runs PG_LOGICAL_CDC. */
        private final Pg pg = new Pg();

        /** SQL Server CDC tuning (v1.7.0) — only consulted when a project runs SQLSERVER_CDC. */
        private final Mssql mssql = new Mssql();

        @Getter
        @Setter
        public static class Logminer {
            /** Rows fetched per V$LOGMNR_CONTENTS batch. */
            private long batchSize = 10_000L;
            /** Idle poll interval when the mining session drained the redo stream. */
            private long idlePollMs = 1_000L;
        }

        @Getter
        @Setter
        public static class Pg {
            /** Rows fetched per pg_logical_slot_get_changes batch. */
            private long batchSize = 1_000L;
            /** Idle poll interval when the slot drained (nothing pending). */
            private long idlePollMs = 500L;
            /** Prefix for the per-project replication slot name; final name is {@code jync_<projectId>}. */
            private String slotPrefix = "jync_";
        }

        @Getter
        @Setter
        public static class Mssql {
            /** Rows fetched per cdc.fn_cdc_get_all_changes batch. */
            private long batchSize = 1_000L;
            /** Idle poll interval between change-table reads. */
            private long idlePollMs = 1_000L;
        }
    }
}
