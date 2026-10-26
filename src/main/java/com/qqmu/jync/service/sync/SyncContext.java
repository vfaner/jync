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

    /**
     * The SQL family each endpoint was resolved to by the factory. Only meaningful for a
     * CUSTOM connection, whose {@link DatabaseType#getFamily()} is GENERIC; null on one side
     * means "derive it from the config, then the type".
     */
    private final DatabaseType.DialectFamily sourceFamily;

    private final DatabaseType.DialectFamily targetFamily;

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
     * Answers whether this run has been asked to stop — the UI's 「停止同步」 pressed while
     * the cycle is already in flight. Checked at the same safe points as the lease
     * ({@link #requireLease()}), so the cycle unwinds at its next committed batch instead of
     * renewing its lease for the rest of a long full load. {@code null} for runs nobody can
     * stop this way: CDC consumers have their own {@code requestStop}, and hand-built test
     * contexts hold no lease at all.
     */
    private final BooleanSupplier stopRequested;

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

    /** The family the source actually speaks; a custom connection's declared one. */
    public DatabaseType.DialectFamily resolveSourceFamily() {
        return resolveFamily(sourceType, sourceConfig, sourceFamily);
    }

    /** The family the target actually speaks; a custom connection's declared one. */
    public DatabaseType.DialectFamily resolveTargetFamily() {
        return resolveFamily(targetType, targetConfig, targetFamily);
    }

    /**
     * The source type every family-sensitive decision must use. For a custom connection this
     * is the preset that speaks its declared family, since the declared {@code CUSTOM} type
     * would otherwise resolve to GENERIC and skip that product's behavior.
     */
    public DatabaseType effectiveSourceType() {
        return effectiveType(sourceType, sourceConfig, sourceFamily);
    }

    /** The target counterpart of {@link #effectiveSourceType()}. */
    public DatabaseType effectiveTargetType() {
        return effectiveType(targetType, targetConfig, targetFamily);
    }

    /**
     * Whether this run's target upserts report insert-vs-update counts. Answered from the
     * target's own URL for a custom connection: standing in a representative preset would
     * claim the preset's {@code useAffectedRows} flag that the user's URL may not carry.
     */
    public boolean upsertCountsDistinguishInsertFromUpdate() {
        if (targetConfig != null) {
            return targetConfig.upsertCountsDistinguishInsertFromUpdate();
        }
        return targetType != null && targetType.upsertCountsDistinguishInsertFromUpdate();
    }

    private static DatabaseType.DialectFamily resolveFamily(DatabaseType type,
                                                            DatabaseConfig config,
                                                            DatabaseType.DialectFamily declared) {
        if (config != null) {
            return config.resolvedFamily();
        }
        if (type != null && type != DatabaseType.CUSTOM) {
            return type.getFamily();
        }
        return declared == null ? DatabaseType.DialectFamily.GENERIC : declared;
    }

    private static DatabaseType effectiveType(DatabaseType type, DatabaseConfig config,
                                              DatabaseType.DialectFamily declared) {
        if (type == null) {
            return null;
        }
        if (type != DatabaseType.CUSTOM) {
            return type;
        }
        DatabaseType representative =
                DatabaseType.representativeOf(resolveFamily(type, config, declared));
        return representative == null ? DatabaseType.CUSTOM : representative;
    }

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

    /**
     * True when a stop was requested through this context. The flag stays set after the
     * abort, which is how the runner tells a user-initiated stop from a failed cycle when it
     * records the outcome.
     */
    public boolean isStopRequested() {
        return stopRequested != null && stopRequested.getAsBoolean();
    }

    /**
     * Renews the lease, aborting the run when it was lost, or earlier when the user asked
     * this run to stop. Both are safe points: every caller sits on a committed batch, so
     * nothing is left half-written by unwinding here.
     */
    public void requireLease() {
        if (isStopRequested()) {
            throw new StopRequestedException("Sync of project '" + projectName()
                    + "' stopped by request; aborting at the next safe point");
        }
        if (!renewLease()) {
            throw new LockLostException("Sync lock lost mid-run (lease expired or reclaimed by"
                    + " another instance); aborting before further writes");
        }
    }
}
