package com.qqmu.jync.service.task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.OracleVersionResolver;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.sync.SyncContext;

import lombok.extern.slf4j.Slf4j;

/** Assembles the {@link SyncContext} for a project: both endpoints, dialects and schemas. */
@Service
@Slf4j
public class SyncContextFactory {

    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final SqlDialectFactory dialectFactory;
    private final OracleVersionResolver oracleVersionResolver;
    private final SyncProperties properties;
    private final ObjectMapper objectMapper;

    public SyncContextFactory(DatabaseConfigRepository databaseConfigRepository,
                              DataSourceManager dataSourceManager,
                              MetadataReaderFactory readerFactory,
                              SqlDialectFactory dialectFactory,
                              OracleVersionResolver oracleVersionResolver,
                              SyncProperties properties,
                              ObjectMapper objectMapper) {
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.dialectFactory = dialectFactory;
        this.oracleVersionResolver = oracleVersionResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the context, resolving each side's effective schema by connecting to it.
     *
     * @throws IllegalStateException when either endpoint is unconfigured or unreachable, since
     *                              a cycle cannot start without both
     */
    public SyncContext build(Project project, String lockOwner, BooleanSupplier lockRenewer) {
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
        // Dialect version for the real product: effective (product vs COMPATIBLE) major,
        // downgraded to the pre-12 emulation when a live grammar probe shows the server does
        // not actually accept AS IDENTITY. DM/YashanDB are routed by the dialect factory
        // itself, not by this number.
        Integer targetMajorVersion = target.getType() == DatabaseType.ORACLE
                ? resolveDialectVersion(target) : null;

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
                .targetDialect(dialectFactory.forType(target.getType(), targetMajorVersion))
                .batchSize(batchSize)
                .lockOwner(lockOwner)
                .lockRenewer(lockRenewer)
                .connectionProvider(dataSourceManager::getConnection)
                .build();
    }

    /** Uses the configured schema, or asks the database what the connection's default is. */
    private String resolveSchema(DatabaseConfig config, MetadataReader reader) {
        if (config.getSchemaName() != null && !config.getSchemaName().isBlank()) {
            return normalizeConfiguredSchema(config.getType(), config.getSchemaName().trim());
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

    /** Oracle-family dictionaries key unquoted identifiers upper case. */
    private String normalizeConfiguredSchema(DatabaseType type, String schema) {
        if (type != null && type.getFamily() == DatabaseType.DialectFamily.ORACLE) {
            return schema.toUpperCase(java.util.Locale.ROOT);
        }
        return schema;
    }

    /**
     * Dialect version for the connected target, or null when it cannot be resolved — the
     * dialect then uses the sequence+trigger emulation, valid on every release.
     */
    private Integer resolveDialectVersion(DatabaseConfig config) {
        try (Connection conn = dataSourceManager.getConnection(config)) {
            return oracleVersionResolver.resolveDialectVersion(conn);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not resolve the database dialect version for '{}'; using the"
                    + " portable pre-12 emulation: {}", config.getName(), e.getMessage());
            return null;
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
