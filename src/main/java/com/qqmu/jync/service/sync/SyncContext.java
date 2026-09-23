package com.qqmu.jync.service.sync;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;

import lombok.Builder;
import lombok.Getter;

/** Everything one sync run needs: both endpoints, their dialects, readers and settings. */
@Getter
@Builder
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

    public Long projectId() {
        return project.getId();
    }

    public String projectName() {
        return project.getName();
    }
}
