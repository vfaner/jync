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
import com.qqmu.jync.service.sync.ConnectionProvider;
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
    private final PoolActivity poolActivity;

    public SyncContextFactory(DatabaseConfigRepository databaseConfigRepository,
                              DataSourceManager dataSourceManager,
                              MetadataReaderFactory readerFactory,
                              SqlDialectFactory dialectFactory,
                              OracleVersionResolver oracleVersionResolver,
                              SyncProperties properties,
                              ObjectMapper objectMapper,
                              PoolActivity poolActivity) {
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.dialectFactory = dialectFactory;
        this.oracleVersionResolver = oracleVersionResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.poolActivity = poolActivity;
    }

    /**
     * Builds the context, resolving each side's effective schema by connecting to it.
     *
     * @throws IllegalStateException when either endpoint is unconfigured or unreachable, since
     *                              a cycle cannot start without both
     */
    public SyncContext build(Project project, String lockOwner, BooleanSupplier lockRenewer) {
        return build(project, lockOwner, lockRenewer, null);
    }

    /**
     * Builds the context, resolving each side's effective schema by connecting to it.
     *
     * @param stopRequested answers whether a running cycle should abort; {@code null} for
     *                      callers that stop their work another way (CDC consumers, tests)
     * @throws IllegalStateException when either endpoint is unconfigured or unreachable, since
     *                              a cycle cannot start without both
     */
    public SyncContext build(Project project, String lockOwner, BooleanSupplier lockRenewer,
                             BooleanSupplier stopRequested) {
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
        applyCdcBaselineDefaults(config);

        DatabaseType.DialectFamily sourceFamily = source.resolvedFamily();
        DatabaseType.DialectFamily targetFamily = target.resolvedFamily();
        MetadataReader sourceReader = readerFactory.forType(source.getType(), sourceFamily);
        MetadataReader targetReader = readerFactory.forType(target.getType(), targetFamily);

        String sourceSchema = resolveSchema(source, sourceReader);
        String targetSchema = resolveSchema(target, targetReader);
        // Dialect version for the real product: effective (product vs COMPATIBLE) major,
        // downgraded to the pre-12 emulation when a live grammar probe shows the server does
        // not actually accept AS IDENTITY. DM/YashanDB are routed by the dialect factory
        // itself, not by this number.
        Integer targetMajorVersion = targetFamily == DatabaseType.DialectFamily.ORACLE
                ? resolveDialectVersion(target) : null;

        // Incremental windows and CDC replay commit in front of live applications, so they
        // keep the global batch budget; the project's own batchSize now tunes the full load
        // only (it was never a scheduling concern, despite living next to the schedule form).
        int batchSize = properties.getBatchSize();
        int fullLoadBatchSize = resolveFullLoadBatchSize(config);
        int fullLoadFetchSize = resolveFullLoadFetchSize();
        int fullLoadParallelism = resolveFullLoadParallelism(config, source, target);

        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(source)
                .targetConfig(target)
                .sourceType(source.getType())
                .targetType(target.getType())
                // The families the readers and dialects were picked by, so the write paths
                // can make the same decisions instead of reading GENERIC off a CUSTOM type.
                .sourceFamily(sourceFamily)
                .targetFamily(targetFamily)
                .sourceSchema(sourceSchema)
                .targetSchema(targetSchema)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(dialectFactory.forType(source.getType(), null, sourceFamily))
                .targetDialect(dialectFactory.forType(target.getType(), targetMajorVersion,
                                targetFamily)
                        // sync.lob.oracle-empty-string-as-empty-clob reaches every bind path
                        // through the dialect; only Oracle acts on it, everyone else returns
                        // itself unchanged.
                        .withEmptyStringAsLob(
                                properties.getLob().isOracleEmptyStringAsEmptyClob()))
                .batchSize(batchSize)
                .fullLoadBatchSize(fullLoadBatchSize)
                .fullLoadFetchSize(fullLoadFetchSize)
                .fullLoadParallelism(fullLoadParallelism)
                .deferSecondaryIndexes(properties.isDeferSecondaryIndexes())
                .lockOwner(lockOwner)
                .lockRenewer(lockRenewer)
                .stopRequested(stopRequested)
                .connectionProvider(new ConnectionProvider() {
                    @Override
                    public Connection open(DatabaseConfig config) throws SQLException {
                        return dataSourceManager.getConnection(config);
                    }

                    @Override
                    public Connection openWaiting(DatabaseConfig config) throws SQLException {
                        // Chunk workers share the pool with every task on this configuration;
                        // they queue for a seat and defer the run instead of failing it.
                        return dataSourceManager.getConnectionOrDefer(config);
                    }
                })
                .build();
    }

    /** Hard ceiling on a full-load batch: a statement must stay small whatever is configured. */
    private static final int MAX_FULL_LOAD_BATCH = 20_000;

    /**
     * Effective rows per full-load batch: the project's own setting, clamped to 1..20000, or
     * the global full-load default when it is absent/non-positive.
     */
    int resolveFullLoadBatchSize(SyncConfig config) {
        Integer requested = config.getBatchSize();
        if (requested == null || requested <= 0) {
            return clampFullLoadBatch(properties.getFullLoadBatchSize());
        }
        return clampFullLoadBatch(requested);
    }

    private int clampFullLoadBatch(int value) {
        if (value > MAX_FULL_LOAD_BATCH) {
            log.warn("Full-load batch size {} exceeds the {} ceiling; using {}", value,
                    MAX_FULL_LOAD_BATCH, MAX_FULL_LOAD_BATCH);
            return MAX_FULL_LOAD_BATCH;
        }
        return Math.max(1, value);
    }

    /** Effective cursor fetch size of the full load, clamped to a sane 1..20000. */
    int resolveFullLoadFetchSize() {
        return clampFullLoadBatch(properties.getFullLoadFetchSize());
    }

    /**
     * Effective full-load parallelism: the project's value when set, else the global one;
     * never above {@link FullLoadCapacity#MAX_PARALLELISM}, and never more workers than the
     * pool leaves free for the cycles that hold seats on it right now.
     */
    int resolveFullLoadParallelism(SyncConfig config, DatabaseConfig source,
                                   DatabaseConfig target) {
        int requested = config.getFullLoadParallelism() != null
                && config.getFullLoadParallelism() > 0
                ? config.getFullLoadParallelism() : properties.getFullLoadParallelism();
        int sourceSeats = poolActivity.activeOn(source == null ? null : source.getId());
        int targetSeats = poolActivity.activeOn(target == null ? null : target.getId());
        int effective = Math.max(1, Math.min(Math.max(requested, 1),
                FullLoadCapacity.effectiveCap(properties, source, target,
                        sourceSeats, targetSeats)));
        if (effective != requested) {
            log.info("Full-load parallelism {} capped to {} by the {} connection pool"
                            + " (seats held: source {}, target {})",
                    requested, effective, Math.max(2, properties.getMaxPoolSize()),
                    sourceSeats, targetSeats);
        }
        return effective;
    }

    /** Uses the configured schema, or asks the database what the connection's default is. */
    private String resolveSchema(DatabaseConfig config, MetadataReader reader) {
        if (config.getSchemaName() != null && !config.getSchemaName().isBlank()) {
            return normalizeConfiguredSchema(config, config.getSchemaName().trim());
        }
        try (Connection conn = dataSourceManager.getConnectionOrDefer(config)) {
            String schema = reader.resolveDefaultSchema(conn);
            log.debug("Resolved default schema for '{}' to '{}'", config.getName(), schema);
            return schema;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot connect to '" + config.getName()
                    + "' to determine its schema: " + e.getMessage(), e);
        }
    }

    /**
     * Oracle-family dictionaries key unquoted identifiers upper case. Uses the resolved
     * family, so a CUSTOM connection that declared Oracle folds exactly like the preset.
     */
    String normalizeConfiguredSchema(DatabaseConfig config, String schema) {
        if (config != null && config.resolvedFamily() == DatabaseType.DialectFamily.ORACLE) {
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

    /**
     * The log-CDC baseline is the only full load a CDC project ever gets, and the form hides
     * the structure/data/indexes switches in that mode. Hidden is not inert: a project saved
     * as structure-only and then switched to CDC would load no rows during the baseline, and
     * the change stream only covers changes after the baseline position. Treat the three as
     * on for CDC execution, without writing back to the stored config — switching back to
     * polling restores the user's original choices, and the dangerous switches
     * ({@code allowDrop}, {@code syncDeletes}) stay exactly as saved.
     */
    static void applyCdcBaselineDefaults(SyncConfig config) {
        if (config == null || !config.getCaptureStrategyOrDefault().isLogCdc()) {
            return;
        }
        config.setSyncStructure(true);
        config.setSyncData(true);
        config.setSyncIndexes(true);
    }

    public String serializeConfig(SyncConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize sync config", e);
        }
    }
}
