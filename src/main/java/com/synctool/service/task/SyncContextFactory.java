package com.synctool.service.task;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synctool.config.SyncProperties;
import com.synctool.dto.SyncConfig;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.Project;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.service.converter.SqlDialectFactory;
import com.synctool.service.metadata.MetadataReader;
import com.synctool.service.metadata.MetadataReaderFactory;
import com.synctool.service.sync.SyncContext;

import lombok.extern.slf4j.Slf4j;

/** Assembles the {@link SyncContext} for a project: both endpoints, dialects and schemas. */
@Service
@Slf4j
public class SyncContextFactory {

    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final SqlDialectFactory dialectFactory;
    private final SyncProperties properties;
    private final ObjectMapper objectMapper;

    public SyncContextFactory(DatabaseConfigRepository databaseConfigRepository,
                              DataSourceManager dataSourceManager,
                              MetadataReaderFactory readerFactory,
                              SqlDialectFactory dialectFactory,
                              SyncProperties properties,
                              ObjectMapper objectMapper) {
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.dialectFactory = dialectFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the context, resolving each side's effective schema by connecting to it.
     *
     * @throws IllegalStateException when either endpoint is unconfigured or unreachable, since
     *                              a cycle cannot start without both
     */
    public SyncContext build(Project project, String lockOwner) {
        if (project.getSourceDbId() == null || project.getTargetDbId() == null) {
            throw new IllegalStateException(
                    "Project '" + project.getName() + "' has no source or target database configured");
        }

        DatabaseConfig source = databaseConfigRepository.findById(project.getSourceDbId())
                .orElseThrow(() -> new IllegalStateException(
                        "Source database config " + project.getSourceDbId() + " no longer exists"));
        DatabaseConfig target = databaseConfigRepository.findById(project.getTargetDbId())
                .orElseThrow(() -> new IllegalStateException(
                        "Target database config " + project.getTargetDbId() + " no longer exists"));

        SyncConfig config = parseConfig(project);

        MetadataReader sourceReader = readerFactory.forType(source.getType());
        MetadataReader targetReader = readerFactory.forType(target.getType());

        String sourceSchema = resolveSchema(source, sourceReader);
        String targetSchema = resolveSchema(target, targetReader);

        int batchSize = config.getBatchSize() != null && config.getBatchSize() > 0
                ? config.getBatchSize() : properties.getBatchSize();

        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(source)
                .targetConfig(target)
                .sourceType(source.getType())
                .targetType(target.getType())
                .sourceSchema(sourceSchema)
                .targetSchema(targetSchema)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(dialectFactory.forType(source.getType()))
                .targetDialect(dialectFactory.forType(target.getType()))
                .batchSize(batchSize)
                .lockOwner(lockOwner)
                .build();
    }

    /** Uses the configured schema, or asks the database what the connection's default is. */
    private String resolveSchema(DatabaseConfig config, MetadataReader reader) {
        if (config.getSchemaName() != null && !config.getSchemaName().isBlank()) {
            return config.getSchemaName().trim();
        }
        try (Connection conn = dataSourceManager.getConnection(config)) {
            String schema = reader.resolveDefaultSchema(conn);
            log.debug("Resolved default schema for '{}' to '{}'", config.getName(), schema);
            return schema;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot connect to '" + config.getName()
                    + "' to determine its schema: " + e.getMessage(), e);
        }
    }

    /** Deserializes the project's stored settings, falling back to defaults when absent. */
    public SyncConfig parseConfig(Project project) {
        String json = project.getSyncConfig();
        if (json == null || json.isBlank()) {
            return new SyncConfig();
        }
        try {
            return objectMapper.readValue(json, SyncConfig.class);
        } catch (Exception e) {
            // A corrupt config would otherwise make the project permanently unstartable.
            log.error("Could not parse sync config for project '{}'; using defaults: {}",
                    project.getName(), e.getMessage());
            return new SyncConfig();
        }
    }

    public String serializeConfig(SyncConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize sync config", e);
        }
    }
}
