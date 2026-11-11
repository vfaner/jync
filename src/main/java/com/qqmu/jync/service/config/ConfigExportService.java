package com.qqmu.jync.service.config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.config.ConfigPackageDtos;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlCheckpoint;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.MetadataSnapshot;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.repository.AiProviderRepository;
import com.qqmu.jync.repository.AlertConfigRepository;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.repository.CdcPositionRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlCheckpointRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.MetadataSnapshotRepository;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.repository.OaAuthConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.repository.SystemSettingRepository;
import com.qqmu.jync.repository.AlertStateRepository;
import com.qqmu.jync.model.AlertState;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Builds a {@code .jync} package: a passphrase-sealed zip of the configuration tables.
 *
 * <p>A logical export rather than a SQL dump — the JSON is written and read by this application,
 * so the package moves between H2, MySQL, PostgreSQL and Oracle without a dialect in sight.
 *
 * <p>The whole read runs in one read-only transaction so the collections are a consistent
 * snapshot; serialization and encryption run outside it so a slow KDF does not hold a connection
 * open.
 */
@Service
public class ConfigExportService {

    /** What the caller writes to the browser, plus the figures the audit entry records. */
    public record ConfigPackage(byte[] bytes,
                                String filename,
                                int entryCount,
                                int databases,
                                int redisTargets,
                                int migrationConnections,
                                int projects,
                                int etlBatches,
                                int etlTasks,
                                int migrationTasks,
                                int aiProviders,
                                int users,
                                int systemSettings,
                                boolean includeState) {
    }

    private static final DateTimeFormatter FILENAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final TransactionTemplate readOnly;
    private final ObjectMapper json;
    private final ConfigPackageWriter writer;
    private final ConfigPackageCrypto packageCrypto;
    private final SyncProperties syncProperties;
    private final CryptoUtil cryptoUtil;
    private final ObjectProvider<BuildProperties> buildProperties;

    private final DatabaseConfigRepository databaseConfigs;
    private final RedisTargetRepository redisTargets;
    private final MigrationConnectionRepository migrationConnections;
    private final ProjectRepository projects;
    private final EtlBatchRepository etlBatches;
    private final EtlTaskRepository etlTasks;
    private final MigrationTaskRepository migrationTasks;
    private final AiProviderRepository aiProviders;
    private final AlertConfigRepository alertConfig;
    private final OaAuthConfigRepository oaAuthConfig;
    private final AppUserRepository users;
    private final SystemSettingRepository systemSettings;
    private final SyncProgressRepository syncProgress;
    private final CdcPositionRepository cdcPositions;
    private final MetadataSnapshotRepository metadataSnapshots;
    private final EtlCheckpointRepository etlCheckpoints;
    private final AlertStateRepository alertStates;

    public ConfigExportService(PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper,
                               ConfigPackageWriter writer,
                               ConfigPackageCrypto packageCrypto,
                               SyncProperties syncProperties,
                               CryptoUtil cryptoUtil,
                               ObjectProvider<BuildProperties> buildProperties,
                               DatabaseConfigRepository databaseConfigs,
                               RedisTargetRepository redisTargets,
                               MigrationConnectionRepository migrationConnections,
                               ProjectRepository projects,
                               EtlBatchRepository etlBatches,
                               EtlTaskRepository etlTasks,
                               MigrationTaskRepository migrationTasks,
                               AiProviderRepository aiProviders,
                               AlertConfigRepository alertConfig,
                               OaAuthConfigRepository oaAuthConfig,
                               AppUserRepository users,
                               SystemSettingRepository systemSettings,
                               SyncProgressRepository syncProgress,
                               CdcPositionRepository cdcPositions,
                               MetadataSnapshotRepository metadataSnapshots,
                               EtlCheckpointRepository etlCheckpoints,
                               AlertStateRepository alertStates) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        // The package format promises a readable ISO timestamp; the application's own mapper
        // writes epoch values for the pages' JavaScript.
        this.json = objectMapper.rebuild()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        this.writer = writer;
        this.packageCrypto = packageCrypto;
        this.syncProperties = syncProperties;
        this.cryptoUtil = cryptoUtil;
        this.buildProperties = buildProperties;
        this.databaseConfigs = databaseConfigs;
        this.redisTargets = redisTargets;
        this.migrationConnections = migrationConnections;
        this.projects = projects;
        this.etlBatches = etlBatches;
        this.etlTasks = etlTasks;
        this.migrationTasks = migrationTasks;
        this.aiProviders = aiProviders;
        this.alertConfig = alertConfig;
        this.oaAuthConfig = oaAuthConfig;
        this.users = users;
        this.systemSettings = systemSettings;
        this.syncProgress = syncProgress;
        this.cdcPositions = cdcPositions;
        this.metadataSnapshots = metadataSnapshots;
        this.etlCheckpoints = etlCheckpoints;
        this.alertStates = alertStates;
    }

    public ConfigPackage build(char[] passphrase, boolean includeState) {
        Snapshot snapshot = readOnly.execute(status -> read());

        Map<String, byte[]> entries = new TreeMap<>();
        entries.put("config/database-configs.json", json(snapshot.databaseConfigs()));
        entries.put("config/redis-targets.json", json(snapshot.redisTargets()));
        entries.put("config/migration-connections.json", json(snapshot.migrationConnections()));
        entries.put("config/projects.json", json(snapshot.projects()));
        entries.put("config/etl-batches.json", json(snapshot.etlBatches()));
        entries.put("config/etl-tasks.json", json(snapshot.etlTasks()));
        entries.put("config/migration-tasks.json", json(snapshot.migrationTasks()));
        entries.put("config/ai-providers.json", json(snapshot.aiProviders()));
        entries.put("config/alert-config.json", json(snapshot.alertConfig()));
        entries.put("config/oa-auth-config.json", json(snapshot.oaAuthConfig()));
        entries.put("config/app-users.json", json(snapshot.users()));
        entries.put("config/system-settings.json", json(snapshot.systemSettings()));
        if (includeState) {
            entries.put("state/sync-progress.json", json(snapshot.syncProgress()));
            entries.put("state/cdc-positions.json", json(snapshot.cdcPositions()));
            entries.put("state/metadata-snapshots.json", json(snapshot.metadataSnapshots()));
            entries.put("state/etl-checkpoints.json", json(snapshot.etlCheckpoints()));
            entries.put("state/alert-states.json", json(snapshot.alertStates()));
        }

        ConfigPackageDtos.Counts counts = new ConfigPackageDtos.Counts(
                snapshot.databaseConfigs().size(),
                snapshot.redisTargets().size(),
                snapshot.migrationConnections().size(),
                snapshot.projects().size(),
                snapshot.etlBatches().size(),
                snapshot.etlTasks().size(),
                snapshot.migrationTasks().size(),
                snapshot.aiProviders().size(),
                snapshot.users().size(),
                snapshot.systemSettings().size());
        BuildProperties build = buildProperties.getIfAvailable();
        entries.put("meta.json", json(new ConfigPackageDtos.Meta(
                ConfigPackageFormat.SCHEMA_VERSION,
                build == null ? "" : build.getVersion(),
                Instant.now(),
                includeState,
                new ConfigPackageDtos.SourceCrypto(syncProperties.getCryptoPassword(),
                        syncProperties.getCryptoSalt(), cryptoUtil.isUsingDefaultKey()),
                counts)));

        entries.put("manifest.json", json(ConfigManifest.of(entries)));

        // The reader refuses a package whose entries unpack past the uncompressed cap, so an
        // export that would exceed it must fail here — otherwise the file the page just
        // downloaded can never be imported back.
        long uncompressed = 0;
        for (byte[] entry : entries.values()) {
            uncompressed += entry.length;
        }
        requireWithinUncompressedLimit(uncompressed);

        byte[] sealed = packageCrypto.encrypt(writer.zip(entries), passphrase, includeState);
        requireWithinPackageLimit(sealed.length);

        return new ConfigPackage(sealed,
                "jync-config-" + FILENAME_STAMP.format(Instant.now()) + ".jync",
                entries.size(),
                counts.databases(), counts.redisTargets(), counts.migrationConnections(),
                counts.projects(), counts.etlBatches(), counts.etlTasks(), counts.migrationTasks(),
                counts.aiProviders(), counts.users(), counts.systemSettings(),
                includeState);
    }

    /** A package this large is not a configuration export; refuse it before anything is written. */
    static void requireWithinPackageLimit(long size) {
        if (size > ConfigPackageFormat.MAX_PACKAGE_BYTES) {
            throw new ConfigPackageException("error.config.export.tooLarge");
        }
    }

    /** The same, for the sum of the entries before they are compressed and sealed. */
    static void requireWithinUncompressedLimit(long size) {
        if (size > ConfigPackageFormat.MAX_UNCOMPRESSED_BYTES) {
            throw new ConfigPackageException("error.config.export.tooLarge");
        }
    }

    private Snapshot read() {
        return new Snapshot(
                databaseConfigs.findAll().stream().map(ConfigExportService::toDto).toList(),
                redisTargets.findAll().stream().map(ConfigExportService::toDto).toList(),
                migrationConnections.findAll().stream().map(ConfigExportService::toDto).toList(),
                projects.findAll().stream().map(ConfigExportService::toDto).toList(),
                etlBatches.findAll().stream().map(ConfigExportService::toDto).toList(),
                etlTasks.findAll().stream().map(ConfigExportService::toDto).toList(),
                migrationTasks.findAll().stream().map(ConfigExportService::toDto).toList(),
                aiProviders.findAll().stream().map(ConfigExportService::toDto).toList(),
                alertConfig.findAll().stream().findFirst()
                        .map(ConfigExportService::toDto)
                        .orElseGet(ConfigExportService::defaultAlertConfig),
                oaAuthConfig.findAll().stream().findFirst()
                        .map(ConfigExportService::toDto)
                        .orElseGet(() -> toDto(new OaAuthConfig())),
                users.findAll().stream().map(ConfigExportService::toDto).toList(),
                systemSettings.findAll().stream().map(ConfigExportService::toDto).toList(),
                syncProgress.findAll().stream().map(ConfigExportService::toDto).toList(),
                cdcPositions.findAll().stream().map(ConfigExportService::toDto).toList(),
                metadataSnapshots.findAll().stream().map(ConfigExportService::toDto).toList(),
                etlCheckpoints.findAll().stream().map(ConfigExportService::toDto).toList(),
                alertStates.findAll().stream().map(ConfigExportService::toDto).toList());
    }

    private byte[] json(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JacksonException e) {
            throw new ConfigPackageException("error.config.export.failed", e);
        }
    }

    // --- entity -> DTO -------------------------------------------------------------------

    private static ConfigPackageDtos.DatabaseConfigDto toDto(DatabaseConfig db) {
        return new ConfigPackageDtos.DatabaseConfigDto(db.getId(), db.getName(), db.getType(),
                db.getDialectFamily(), db.getRole(), db.getHost(), db.getPort(),
                db.getDatabaseName(), db.getSchemaName(), db.getUsername(), db.getPassword(),
                db.getMinerUsername(), db.getMinerPassword(), db.getCustomUrl(),
                db.getCustomDriver(), db.getCustomJarPath(), db.getExtraParams());
    }

    private static ConfigPackageDtos.RedisTargetDto toDto(RedisTarget target) {
        return new ConfigPackageDtos.RedisTargetDto(target.getId(), target.getName(),
                target.getHost(), target.getPort(), target.getDatabaseIndex(), target.getUsername(),
                target.getPasswordEnc(), target.isTlsEnabled(), target.isVerifyTls(),
                target.isClusterMode(), target.getConnectTimeoutMs(), target.getCommandTimeoutMs(),
                target.getDescription());
    }

    private static ConfigPackageDtos.MigrationConnectionDto toDto(MigrationConnection connection) {
        return new ConfigPackageDtos.MigrationConnectionDto(connection.getId(),
                connection.getName(), connection.getKind(), connection.getBaseUrl(),
                connection.getDatabaseName(), connection.getSchemaName(), connection.getAuthType(),
                connection.getUsername(), connection.getPasswordEnc(), connection.getApiKeyEnc(),
                connection.getExtraParams(), connection.isVerifyTls(),
                connection.getConnectTimeoutMs(), connection.getRequestTimeoutMs(),
                connection.getDescription());
    }

    private static ConfigPackageDtos.ProjectDto toDto(Project project) {
        return new ConfigPackageDtos.ProjectDto(project.getId(), project.getName(),
                project.getDescription(), project.getSourceDbId(), project.getTargetDbId(),
                project.getTargetKind(), project.getTargetRedisId(), project.getSyncConfig(),
                project.getEnabled());
    }

    private static ConfigPackageDtos.EtlBatchDto toDto(EtlBatch batch) {
        return new ConfigPackageDtos.EtlBatchDto(batch.getId(), batch.getName(),
                batch.getDescription(), batch.getSourceDbId(), batch.getTargetDbId(),
                batch.getCronExpression(), batch.getEnabled(), batch.getTimeoutSeconds());
    }

    private static ConfigPackageDtos.EtlTaskDto toDto(EtlTask task) {
        return new ConfigPackageDtos.EtlTaskDto(task.getId(), task.getName(), task.getDescription(),
                task.getSourceDbId(), task.getTargetDbId(), task.getBatchId(), task.getSourceSql(),
                task.getSourceCollection(), task.getSourceFilter(), task.getPreSql(),
                task.getPostSql(), task.getTargetTable(), task.getWriteMode(),
                task.getRangeCondition(), task.getResumeKeyColumn(),
                task.getUpsertKeyColumns(), task.getCronExpression(),
                task.getEnabled(), task.getTimeoutSeconds());
    }

    private static ConfigPackageDtos.MigrationTaskDto toDto(MigrationTask task) {
        return new ConfigPackageDtos.MigrationTaskDto(task.getId(), task.getName(),
                task.getDescription(), task.getType(), task.getSourceConnectionId(),
                task.getTargetConnectionId(), task.getOptionsJson(), task.getCronExpression(),
                task.getEnabled(), task.getTimeoutSeconds());
    }

    private static ConfigPackageDtos.AiProviderDto toDto(AiProvider provider) {
        return new ConfigPackageDtos.AiProviderDto(provider.getId(), provider.getName(),
                provider.getProtocol(), provider.getBaseUrl(), provider.getModel(),
                provider.getApiKey(), provider.isEnabled(), provider.getMaxTokens(),
                provider.getReasoningEffort(), provider.getTimeoutSeconds());
    }

    private static ConfigPackageDtos.AlertConfigDto toDto(AlertConfig config) {
        return new ConfigPackageDtos.AlertConfigDto(config.isEnabled(), config.getWebhookUrl(),
                config.getFormat(), config.getCategories(), config.getCooldownMinutes());
    }

    /** An installation that never opened the alerting page still exports a usable default. */
    private static ConfigPackageDtos.AlertConfigDto defaultAlertConfig() {
        return new ConfigPackageDtos.AlertConfigDto(false, null,
                com.qqmu.jync.model.AlertWebhookFormat.GENERIC, null, 30);
    }

    private static ConfigPackageDtos.OaAuthConfigDto toDto(OaAuthConfig config) {
        return new ConfigPackageDtos.OaAuthConfigDto(config.isEnabled(), config.getMode(),
                config.getAdminUsers(), config.getLoginEntryUrl(), config.getLogoutRedirectUrl(),
                config.getLocalLoginEnabled(), config.getUserHeader(), config.getAdminHeader(),
                config.getSecretHeaderName(), config.getSecretHeaderValue(), config.getValidateUrl(),
                config.getValidateMethod(), config.getTicketParam(), config.getLoginUrl(),
                config.getRequestFormat(), config.getUsernameField(), config.getPasswordField(),
                config.getSuccessField(), config.getSuccessValue(), config.getUserField(),
                config.getAdminField(), config.getAdminValue(), config.getTimeoutMs(),
                config.isAllowInsecureHttp());
    }

    private static ConfigPackageDtos.AppUserDto toDto(AppUser user) {
        return new ConfigPackageDtos.AppUserDto(user.getUsername(), user.getPasswordHash(),
                user.getRole());
    }

    private static ConfigPackageDtos.SystemSettingDto toDto(SystemSetting setting) {
        return new ConfigPackageDtos.SystemSettingDto(setting.getKey(), setting.getValue());
    }

    private static ConfigPackageDtos.SyncProgressDto toDto(SyncProgress progress) {
        return new ConfigPackageDtos.SyncProgressDto(progress.getProjectId(),
                progress.getObjectType(), progress.getObjectName(), progress.getLastSyncValue(),
                progress.getCursorColumn(), progress.getCursorStrategy(), progress.getResumableLoad(),
                progress.getInitialLoadDone(), progress.getRowsSyncedTotal(),
                progress.getLastSyncTime());
    }

    private static ConfigPackageDtos.CdcPositionDto toDto(CdcPosition position) {
        return new ConfigPackageDtos.CdcPositionDto(position.getProjectId(),
                position.getBinlogFile(), position.getBinlogPos(), position.getGtidSet(),
                position.getServerId(), position.getScn(), position.getLsn(),
                position.getSchemaSignatures(), position.getProjectionSignature(),
                position.isBaselineDone());
    }

    private static ConfigPackageDtos.MetadataSnapshotDto toDto(MetadataSnapshot snapshot) {
        return new ConfigPackageDtos.MetadataSnapshotDto(snapshot.getProjectId(),
                snapshot.getObjectType(), snapshot.getObjectName(), snapshot.getSnapshotJson(),
                snapshot.getContentHash());
    }

    private static ConfigPackageDtos.EtlCheckpointDto toDto(EtlCheckpoint checkpoint) {
        return new ConfigPackageDtos.EtlCheckpointDto(checkpoint.getTaskId(),
                checkpoint.getKeyType(), checkpoint.getKeyValue());
    }

    private static ConfigPackageDtos.AlertStateDto toDto(AlertState state) {
        return new ConfigPackageDtos.AlertStateDto(state.getCategory(), state.getProjectId(),
                state.getObjectKey(), state.getSubject(), state.isActive(),
                state.getFirstSeenAt(), state.getLastNotifiedAt(), state.getLastEventAt());
    }

    private record Snapshot(List<ConfigPackageDtos.DatabaseConfigDto> databaseConfigs,
                            List<ConfigPackageDtos.RedisTargetDto> redisTargets,
                            List<ConfigPackageDtos.MigrationConnectionDto> migrationConnections,
                            List<ConfigPackageDtos.ProjectDto> projects,
                            List<ConfigPackageDtos.EtlBatchDto> etlBatches,
                            List<ConfigPackageDtos.EtlTaskDto> etlTasks,
                            List<ConfigPackageDtos.MigrationTaskDto> migrationTasks,
                            List<ConfigPackageDtos.AiProviderDto> aiProviders,
                            ConfigPackageDtos.AlertConfigDto alertConfig,
                            ConfigPackageDtos.OaAuthConfigDto oaAuthConfig,
                            List<ConfigPackageDtos.AppUserDto> users,
                            List<ConfigPackageDtos.SystemSettingDto> systemSettings,
                            List<ConfigPackageDtos.SyncProgressDto> syncProgress,
                            List<ConfigPackageDtos.CdcPositionDto> cdcPositions,
                            List<ConfigPackageDtos.MetadataSnapshotDto> metadataSnapshots,
                            List<ConfigPackageDtos.EtlCheckpointDto> etlCheckpoints,
                            List<ConfigPackageDtos.AlertStateDto> alertStates) {
    }
}
