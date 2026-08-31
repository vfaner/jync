package com.synctool.service.sync;

import com.synctool.dto.SyncConfig;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.model.Project;
import com.synctool.service.converter.SqlDialect;
import com.synctool.service.metadata.MetadataReader;

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
