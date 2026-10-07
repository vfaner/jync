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

    /** Rows per JDBC batch for this project, already resolved against the global default. */
    private final int batchSize;

    /** Identifies the node/thread holding the sync lock, for logging and lock renewal. */
    private final String lockOwner;

    /**
     * Extends the database lease while the run is in progress. Carried on the context so deep
     * write paths can renew at each commit point without threading it through every signature.
     * May be {@code null} for runs that hold no lease (e.g. unit tests); renewal is a no-op then.
     */
    private final BooleanSupplier lockRenewer;

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
