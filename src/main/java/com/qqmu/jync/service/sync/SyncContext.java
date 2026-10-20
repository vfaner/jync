package com.qqmu.jync.service.sync;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;

import java.util.function.BooleanSupplier;

import lombok.Builder;
import lombok.Getter;

/** Everything one sync run needs: both endpoints, their dialects, readers and settings. */
@Getter
@Builder(toBuilder = true)
public class SyncContext {

    private final Project project;

    private final SyncConfig config;

    private final DatabaseConfig sourceConfig;

    private final DatabaseConfig targetConfig;

    private final DatabaseType sourceType;

    private final DatabaseType targetType;

    private final String sourceSchema;

    private final String targetSchema;

    private final MetadataReader sourceReader;

    private final MetadataReader targetReader;

    private final SqlDialect sourceDialect;

    private final SqlDialect targetDialect;

    /**
     * Rows per JDBC batch for incremental windows and CDC replay, already resolved against
     * the global default. The full load has its own budget below.
     */
    private final int batchSize;

    /**
     * Rows per JDBC batch of the full load; 0 (hand-built contexts) means "use {@link #batchSize}".
     * Resolved by the factory from the project's override or {@code sync.full-load-batch-size}.
     */
    private final int fullLoadBatchSize;

    /** Cursor fetch size of the full load; 0 means "use {@code sync.fetch-size}". */
    private final int fullLoadFetchSize;

    /**
     * Parallel workers this project wants for its chunked full load; 0 falls back to the
     * global value. Already capped by the connection pool in the factory, and still subject
     * to the table-shape checks in {@link DataSyncService}.
     */
    private final int fullLoadParallelism;

    /**
     * Whether non-unique secondary indexes of a <em>newly created</em> table are held back
     * until its initial full load has committed. Resolved by the factory from
     * {@code sync.defer-secondary-indexes}. Hand-built contexts default to false (create
     * everything up front), which is also the pre-deferral behavior.
     */
    private final boolean deferSecondaryIndexes;

    /** Identifies the node/thread holding the sync lock, for logging and lock renewal. */
    private final String lockOwner;

    /**
     * Extends the database lease while the run is in progress. Carried on the context so deep
     * write paths can renew at each commit point without threading it through every signature.
     * May be {@code null} for runs that hold no lease (e.g. unit tests); renewal is a no-op then.
     */
    private final BooleanSupplier lockRenewer;

    /**
     * Opens additional pooled connections for parallel work inside one cycle (each parallel
     * full-load worker borrows its own source and target connection per chunk). May be
     * {@code null} for runs that never parallelize (e.g. unit tests); parallel features
     * degrade to sequential then.
     */
    private final ConnectionProvider connectionProvider;

    /**
     * CDC 基线全量专用标记,普通周期 false:BINLOG_CDC 项目的常规轮询周期必须跳过数据
     * 阶段,只有基线跑({@code CdcBaselineService.runBaseline})才真正做全量写入。
     */
    private final boolean cdcBaselineRun;

    public Long projectId() {
        return project.getId();
    }

    public String projectName() {
        return project.getName();
    }

    /**
     * Renews the lease. Returns {@code true} when there is no renewer (nothing to lose) or the
     * lease was extended; {@code false} means it was lost and the run must stop before further
     * writes.
     */
    public boolean renewLease() {
        return lockRenewer == null || lockRenewer.getAsBoolean();
    }

    /** Renews the lease, aborting the run with {@link LockLostException} when it was lost. */
    public void requireLease() {
        if (!renewLease()) {
            throw new LockLostException("Sync lock lost mid-run (lease expired or reclaimed by"
                    + " another instance); aborting before further writes");
        }
    }
}
