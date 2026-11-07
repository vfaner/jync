package com.qqmu.jync.service.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.config.ConfigPackageDtos;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertState;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.CaptureStrategy;
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
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.AiProviderRepository;
import com.qqmu.jync.repository.AlertConfigRepository;
import com.qqmu.jync.repository.AlertStateRepository;
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

/**
 * Restores a configuration package into this deployment's metadata database.
 *
 * <p>Only an empty configuration is accepted. Merging into a database that already holds projects
 * would have to decide, per row, whether the package or the local copy wins — and answering that
 * wrongly duplicates every project or half-adopts one. The user clears the target first (a fresh
 * MySQL/PostgreSQL/Oracle schema is the normal case), and everyone involved can then reason about
 * the result: what was in the package is what is now in the database.
 *
 * <p>Ids in the package belong to the exporting database. Every row is inserted fresh and the
 * references between rows are rewritten to the ids this database assigned.
 */
@Service
public class ConfigImportService {

    /** What the page reports after a successful import. */
    public record ImportResult(int databases,
                               int redisTargets,
                               int migrationConnections,
                               int projects,
                               int etlBatches,
                               int etlTasks,
                               int migrationTasks,
                               int aiProviders,
                               int usersInserted,
                               int usersKept,
                               int systemSettings,
                               boolean stateImported,
                               boolean httpsReset,
                               List<String> warningKeys) {
    }

    private static final String REQUIRE_HTTPS = "security.require-https";

    private final ConfigPackageReader reader;
    private final ConfigSecretTranslator translator;
    private final ConfigImportRuntime runtime;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

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
    private final com.qqmu.jync.service.settings.SystemSettingService settingService;
    private final SyncProgressRepository syncProgress;
    private final CdcPositionRepository cdcPositions;
    private final MetadataSnapshotRepository metadataSnapshots;
    private final EtlCheckpointRepository etlCheckpoints;
    private final AlertStateRepository alertStates;

    public ConfigImportService(ConfigPackageReader reader,
                               ConfigSecretTranslator translator,
                               ConfigImportRuntime runtime,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper,
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
                               com.qqmu.jync.service.settings.SystemSettingService settingService,
                               SyncProgressRepository syncProgress,
                               CdcPositionRepository cdcPositions,
                               MetadataSnapshotRepository metadataSnapshots,
                               EtlCheckpointRepository etlCheckpoints,
                               AlertStateRepository alertStates) {
        this.reader = reader;
        this.translator = translator;
        this.runtime = runtime;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
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
        this.settingService = settingService;
        this.syncProgress = syncProgress;
        this.cdcPositions = cdcPositions;
        this.metadataSnapshots = metadataSnapshots;
        this.etlCheckpoints = etlCheckpoints;
        this.alertStates = alertStates;
    }

    /**
     * Opens and restores a package. Each row the package carries is inserted; the target must
     * already be empty.
     *
     * @param requestSecure whether the import request arrived over HTTPS — the one condition under
     *                      which a forced-HTTPS setting from the package may stay on
     */
    public ImportResult importPackage(byte[] packageBytes, char[] passphrase, boolean requestSecure) {
        ConfigPackageReader.Content content = reader.read(packageBytes, passphrase);
        ImportResult result = transaction.execute(status -> restore(content, requestSecure));
        // The settings rows were written through the repository, bypassing the service, so the
        // cache revision that the HTTPS resolver checks on every request must be bumped here —
        // after the commit, and only when the import did not roll back.
        settingService.markChanged();
        // Only after the commit: the schedulers read the imported rows, and a rollback must
        // leave the running process untouched.
        runtime.rearm();
        return result;
    }

    private ImportResult restore(ConfigPackageReader.Content content, boolean requestSecure) {
        requireEmptyDatabase();
        validateReferences(content);

        List<String> warnings = new ArrayList<>();
        if (content.meta().sourceCrypto() != null
                && content.meta().sourceCrypto().usingDefaultKey()) {
            warnings.add("warn.config.import.defaultSourceKey");
        }
        for (ConfigPackageDtos.DatabaseConfigDto db : content.databaseConfigs()) {
            if (db.customJarPath() != null && !db.customJarPath().isBlank()
                    && !Files.exists(Path.of(db.customJarPath()))
                    && !warnings.contains("warn.config.import.customJarMissing")) {
                warnings.add("warn.config.import.customJarMissing");
            }
        }

        Secrets secrets = translateAll(content);

        Map<Long, Long> databaseIds = new HashMap<>();
        for (ConfigPackageDtos.DatabaseConfigDto dto : content.databaseConfigs()) {
            DatabaseConfig db = new DatabaseConfig();
            db.setName(dto.name());
            db.setType(dto.type());
            db.setDialectFamily(dto.dialectFamily());
            db.setRole(dto.role());
            db.setHost(dto.host());
            db.setPort(dto.port());
            db.setDatabaseName(dto.databaseName());
            db.setSchemaName(dto.schemaName());
            db.setUsername(dto.username());
            db.setPassword(secrets.databasePassword.get(dto.id()));
            db.setMinerUsername(dto.minerUsername());
            db.setMinerPassword(secrets.databaseMinerPassword.get(dto.id()));
            db.setCustomUrl(dto.customUrl());
            db.setCustomDriver(dto.customDriver());
            db.setCustomJarPath(dto.customJarPath());
            db.setExtraParams(dto.extraParams());
            databaseIds.put(dto.id(), databaseConfigs.save(db).getId());
        }

        Map<Long, Long> redisIds = new HashMap<>();
        for (ConfigPackageDtos.RedisTargetDto dto : content.redisTargets()) {
            RedisTarget target = new RedisTarget();
            target.setName(dto.name());
            target.setHost(dto.host());
            target.setPort(dto.port());
            target.setDatabaseIndex(dto.databaseIndex());
            target.setUsername(dto.username());
            target.setPasswordEnc(secrets.redisPassword.get(dto.id()));
            target.setTlsEnabled(dto.tlsEnabled());
            target.setVerifyTls(dto.verifyTls());
            target.setClusterMode(dto.clusterMode());
            target.setConnectTimeoutMs(dto.connectTimeoutMs());
            target.setCommandTimeoutMs(dto.commandTimeoutMs());
            target.setDescription(dto.description());
            redisIds.put(dto.id(), redisTargets.save(target).getId());
        }

        Map<Long, Long> migrationIds = new HashMap<>();
        for (ConfigPackageDtos.MigrationConnectionDto dto : content.migrationConnections()) {
            MigrationConnection connection = new MigrationConnection();
            connection.setName(dto.name());
            connection.setKind(dto.kind());
            connection.setBaseUrl(dto.baseUrl());
            connection.setDatabaseName(dto.databaseName());
            connection.setSchemaName(dto.schemaName());
            connection.setAuthType(dto.authType());
            connection.setUsername(dto.username());
            connection.setPasswordEnc(secrets.migrationPassword.get(dto.id()));
            connection.setApiKeyEnc(secrets.migrationApiKey.get(dto.id()));
            connection.setExtraParams(dto.extraParams());
            connection.setVerifyTls(dto.verifyTls());
            connection.setConnectTimeoutMs(dto.connectTimeoutMs());
            connection.setRequestTimeoutMs(dto.requestTimeoutMs());
            connection.setDescription(dto.description());
            migrationIds.put(dto.id(), migrationConnections.save(connection).getId());
        }

        Map<Long, Long> projectIds = new HashMap<>();
        // The stored config decides whether a restored CDC position is still meaningful
        // (see keepsRestoredBaselineFlag), so keep the exported JSON by its exported id.
        Map<Long, String> projectSyncConfigs = new HashMap<>();
        for (ConfigPackageDtos.ProjectDto dto : content.projects()) {
            Project project = new Project();
            project.setName(dto.name());
            project.setDescription(dto.description());
            project.setSourceDbId(map(databaseIds, dto.sourceDbId()));
            project.setTargetDbId(map(databaseIds, dto.targetDbId()));
            project.setTargetKind(dto.targetKind());
            project.setTargetRedisId(map(redisIds, dto.targetRedisId()));
            project.setSyncConfig(dto.syncConfig());
            project.setEnabled(dto.enabled());
            projectIds.put(dto.id(), projects.save(project).getId());
            projectSyncConfigs.put(dto.id(), dto.syncConfig());
        }

        Map<Long, Long> batchIds = new HashMap<>();
        for (ConfigPackageDtos.EtlBatchDto dto : content.etlBatches()) {
            EtlBatch batch = new EtlBatch();
            batch.setName(dto.name());
            batch.setDescription(dto.description());
            batch.setSourceDbId(map(databaseIds, dto.sourceDbId()));
            batch.setTargetDbId(map(databaseIds, dto.targetDbId()));
            batch.setCronExpression(dto.cronExpression());
            batch.setEnabled(dto.enabled());
            batch.setTimeoutSeconds(dto.timeoutSeconds());
            batchIds.put(dto.id(), etlBatches.save(batch).getId());
        }

        Map<Long, Long> taskIds = new HashMap<>();
        for (ConfigPackageDtos.EtlTaskDto dto : content.etlTasks()) {
            EtlTask task = new EtlTask();
            task.setName(dto.name());
            task.setDescription(dto.description());
            task.setSourceDbId(map(databaseIds, dto.sourceDbId()));
            task.setTargetDbId(map(databaseIds, dto.targetDbId()));
            task.setBatchId(map(batchIds, dto.batchId()));
            task.setSourceSql(dto.sourceSql());
            task.setSourceCollection(dto.sourceCollection());
            task.setSourceFilter(dto.sourceFilter());
            task.setPreSql(dto.preSql());
            task.setPostSql(dto.postSql());
            task.setTargetTable(dto.targetTable());
            task.setWriteMode(dto.writeMode());
            task.setRangeCondition(dto.rangeCondition());
            task.setResumeKeyColumn(dto.resumeKeyColumn());
            task.setCronExpression(dto.cronExpression());
            task.setEnabled(dto.enabled());
            task.setTimeoutSeconds(dto.timeoutSeconds());
            taskIds.put(dto.id(), etlTasks.save(task).getId());
        }

        for (ConfigPackageDtos.MigrationTaskDto dto : content.migrationTasks()) {
            MigrationTask task = new MigrationTask();
            task.setName(dto.name());
            task.setDescription(dto.description());
            task.setType(dto.type());
            task.setSourceConnectionId(map(migrationIds, dto.sourceConnectionId()));
            task.setTargetConnectionId(map(migrationIds, dto.targetConnectionId()));
            task.setOptionsJson(dto.optionsJson());
            task.setCronExpression(dto.cronExpression());
            task.setEnabled(dto.enabled());
            task.setTimeoutSeconds(dto.timeoutSeconds());
            migrationTasks.save(task);
        }

        for (ConfigPackageDtos.AiProviderDto dto : content.aiProviders()) {
            AiProvider provider = new AiProvider();
            provider.setName(dto.name());
            provider.setProtocol(dto.protocol());
            provider.setBaseUrl(dto.baseUrl());
            provider.setModel(dto.model());
            provider.setApiKey(secrets.aiApiKey.get(dto.id()));
            provider.setEnabled(dto.enabled());
            provider.setMaxTokens(dto.maxTokens());
            provider.setReasoningEffort(dto.reasoningEffort());
            provider.setTimeoutSeconds(dto.timeoutSeconds());
            aiProviders.save(provider);
        }

        saveAlertConfig(content.alertConfig(), secrets.alertWebhook);
        saveOaAuthConfig(content.oaAuthConfig(), secrets.oaSecret);

        int[] userCounts = mergeUsers(content.users());
        int[] settingCounts = saveSystemSettings(content.systemSettings(), requestSecure, warnings);

        boolean stateImported = false;
        if (content.meta().includeState()) {
            importState(content.state(), projectIds, taskIds, projectSyncConfigs);
            stateImported = true;
        }

        return new ImportResult(
                content.databaseConfigs().size(), content.redisTargets().size(),
                content.migrationConnections().size(), content.projects().size(),
                content.etlBatches().size(), content.etlTasks().size(),
                content.migrationTasks().size(), content.aiProviders().size(),
                userCounts[0], userCounts[1], settingCounts[0], stateImported,
                settingCounts[1] == 1, List.copyOf(warnings));
    }

    private void requireEmptyDatabase() {
        if (databaseConfigs.count() > 0 || redisTargets.count() > 0
                || migrationConnections.count() > 0 || projects.count() > 0
                || etlBatches.count() > 0 || etlTasks.count() > 0
                || migrationTasks.count() > 0 || aiProviders.count() > 0
                || syncProgress.count() > 0 || cdcPositions.count() > 0
                || metadataSnapshots.count() > 0 || etlCheckpoints.count() > 0
                || alertStates.count() > 0) {
            throw new ConfigPackageException("error.config.import.notEmpty");
        }
    }

    private void validateReferences(ConfigPackageReader.Content content) {
        Set<Long> databaseIds = ids(content.databaseConfigs(),
                ConfigPackageDtos.DatabaseConfigDto::id);
        Set<Long> redisIds = ids(content.redisTargets(), ConfigPackageDtos.RedisTargetDto::id);
        Set<Long> migrationIds = ids(content.migrationConnections(),
                ConfigPackageDtos.MigrationConnectionDto::id);
        Set<Long> batchIds = ids(content.etlBatches(), ConfigPackageDtos.EtlBatchDto::id);

        for (ConfigPackageDtos.ProjectDto project : content.projects()) {
            require(databaseIds, project.sourceDbId());
            require(databaseIds, project.targetDbId());
            require(redisIds, project.targetRedisId());
        }
        for (ConfigPackageDtos.EtlBatchDto batch : content.etlBatches()) {
            require(databaseIds, batch.sourceDbId());
            require(databaseIds, batch.targetDbId());
        }
        for (ConfigPackageDtos.EtlTaskDto task : content.etlTasks()) {
            require(databaseIds, task.sourceDbId());
            require(databaseIds, task.targetDbId());
            require(batchIds, task.batchId());
        }
        for (ConfigPackageDtos.MigrationTaskDto task : content.migrationTasks()) {
            require(migrationIds, task.sourceConnectionId());
            require(migrationIds, task.targetConnectionId());
        }
    }

    private static void require(Set<Long> known, Long id) {
        if (id != null && !known.contains(id)) {
            throw new ConfigPackageException("error.config.import.referenceMissing");
        }
    }

    private static <T> Set<Long> ids(List<T> items, java.util.function.Function<T, Long> id) {
        Set<Long> ids = new HashSet<>();
        for (T item : items) {
            ids.add(id.apply(item));
        }
        return ids;
    }

    private static Long map(Map<Long, Long> ids, Long oldId) {
        if (oldId == null) {
            return null;
        }
        Long mapped = ids.get(oldId);
        if (mapped == null) {
            throw new ConfigPackageException("error.config.import.referenceMissing");
        }
        return mapped;
    }

    private Secrets translateAll(ConfigPackageReader.Content content) {
        ConfigPackageDtos.SourceCrypto source = content.meta().sourceCrypto();
        String sourcePassword = source == null ? null : source.password();
        String sourceSalt = source == null ? null : source.salt();
        Secrets secrets = new Secrets();
        for (ConfigPackageDtos.DatabaseConfigDto dto : content.databaseConfigs()) {
            secrets.databasePassword.put(dto.id(),
                    translator.toTargetKey(dto.password(), sourcePassword, sourceSalt));
            secrets.databaseMinerPassword.put(dto.id(),
                    translator.toTargetKey(dto.minerPassword(), sourcePassword, sourceSalt));
        }
        for (ConfigPackageDtos.RedisTargetDto dto : content.redisTargets()) {
            secrets.redisPassword.put(dto.id(),
                    translator.toTargetKey(dto.passwordEnc(), sourcePassword, sourceSalt));
        }
        for (ConfigPackageDtos.MigrationConnectionDto dto : content.migrationConnections()) {
            secrets.migrationPassword.put(dto.id(),
                    translator.toTargetKey(dto.passwordEnc(), sourcePassword, sourceSalt));
            secrets.migrationApiKey.put(dto.id(),
                    translator.toTargetKey(dto.apiKeyEnc(), sourcePassword, sourceSalt));
        }
        for (ConfigPackageDtos.AiProviderDto dto : content.aiProviders()) {
            secrets.aiApiKey.put(dto.id(),
                    translator.toTargetKey(dto.apiKey(), sourcePassword, sourceSalt));
        }
        secrets.alertWebhook = translator.toTargetKey(content.alertConfig().webhookUrl(),
                sourcePassword, sourceSalt);
        secrets.oaSecret = translator.toTargetKey(content.oaAuthConfig().secretHeaderValue(),
                sourcePassword, sourceSalt);
        return secrets;
    }

    private void saveAlertConfig(ConfigPackageDtos.AlertConfigDto dto, String webhook) {
        AlertConfig config = alertConfig.findAll().stream().findFirst().orElseGet(() -> {
            // The singleton's id is assigned by hand; there is no sequence to generate it.
            AlertConfig fresh = new AlertConfig();
            fresh.setId(1L);
            return fresh;
        });
        config.setEnabled(dto.enabled());
        config.setWebhookUrl(webhook);
        config.setFormat(dto.format());
        config.setCategories(dto.categories());
        config.setCooldownMinutes(dto.cooldownMinutes());
        config.setUpdatedAt(Instant.now());
        alertConfig.save(config);
    }

    private void saveOaAuthConfig(ConfigPackageDtos.OaAuthConfigDto dto, String secret) {
        OaAuthConfig config = oaAuthConfig.findAll().stream().findFirst().orElseGet(() -> {
            OaAuthConfig fresh = new OaAuthConfig();
            fresh.setId(1L);
            return fresh;
        });
        config.setEnabled(dto.enabled());
        config.setMode(dto.mode());
        config.setAdminUsers(dto.adminUsers());
        config.setLoginEntryUrl(dto.loginEntryUrl());
        config.setLogoutRedirectUrl(dto.logoutRedirectUrl());
        config.setLocalLoginEnabled(dto.localLoginEnabled());
        config.setUserHeader(dto.userHeader());
        config.setAdminHeader(dto.adminHeader());
        config.setSecretHeaderName(dto.secretHeaderName());
        config.setSecretHeaderValue(secret);
        config.setValidateUrl(dto.validateUrl());
        config.setValidateMethod(dto.validateMethod());
        config.setTicketParam(dto.ticketParam());
        config.setLoginUrl(dto.loginUrl());
        config.setRequestFormat(dto.requestFormat());
        config.setUsernameField(dto.usernameField());
        config.setPasswordField(dto.passwordField());
        config.setSuccessField(dto.successField());
        config.setSuccessValue(dto.successValue());
        config.setUserField(dto.userField());
        config.setAdminField(dto.adminField());
        config.setAdminValue(dto.adminValue());
        config.setTimeoutMs(dto.timeoutMs());
        config.setAllowInsecureHttp(dto.allowInsecureHttp());
        config.setUpdatedAt(Instant.now());
        oaAuthConfig.save(config);
    }

    /** Returns {@code [inserted, kept]}. */
    private int[] mergeUsers(List<ConfigPackageDtos.AppUserDto> packageUsers) {
        int inserted = 0;
        int kept = 0;
        for (ConfigPackageDtos.AppUserDto dto : packageUsers) {
            Optional<AppUser> existing = users.findByUsername(dto.username());
            if (existing.isPresent()) {
                // The target's credentials win: the package's hash would sign in with a password
                // the person running the import may not even know.
                kept++;
                continue;
            }
            AppUser user = new AppUser();
            user.setUsername(dto.username());
            user.setPasswordHash(dto.passwordHash());
            user.setRole(dto.role());
            users.save(user);
            inserted++;
        }
        if (users.countByRole(UserRole.ADMIN) == 0) {
            throw new ConfigPackageException("error.config.import.noAdmin");
        }
        return new int[] {inserted, kept};
    }

    /** Returns {@code [settingsWritten, httpsReset ? 1 : 0]}. */
    private int[] saveSystemSettings(List<ConfigPackageDtos.SystemSettingDto> settings,
                                     boolean requestSecure, List<String> warnings) {
        int written = 0;
        boolean httpsReset = false;
        for (ConfigPackageDtos.SystemSettingDto dto : settings) {
            if (dto.key() == null || dto.key().isBlank()) {
                continue;
            }
            String value = dto.value();
            if (REQUIRE_HTTPS.equals(dto.key()) && truthy(value) && !requestSecure) {
                // Restoring "must use HTTPS" while the very request carrying it arrived in the
                // clear would lock the administrator out of the page they are standing on.
                value = "false";
                httpsReset = true;
                if (!warnings.contains("warn.config.import.httpsReset")) {
                    warnings.add("warn.config.import.httpsReset");
                }
            }
            SystemSetting setting = systemSettings.findById(dto.key())
                    .orElseGet(() -> {
                        SystemSetting fresh = new SystemSetting();
                        fresh.setKey(dto.key());
                        return fresh;
                    });
            setting.setValue(value);
            setting.setUpdatedAt(Instant.now());
            systemSettings.save(setting);
            written++;
        }
        return new int[] {written, httpsReset ? 1 : 0};
    }

    /**
     * Restores the runtime state rows. A row whose project or task is not in the package is
     * skipped rather than aborting: the state is a cache of progress, and one orphan does not
     * justify refusing the configuration the operator actually came for.
     */
    private void importState(ConfigPackageDtos.State state,
                             Map<Long, Long> projectIds,
                             Map<Long, Long> taskIds,
                             Map<Long, String> projectSyncConfigs) {
        for (ConfigPackageDtos.SyncProgressDto dto : state.syncProgress()) {
            Long projectId = projectIds.get(dto.projectId());
            if (projectId == null) {
                continue;
            }
            SyncProgress progress = new SyncProgress();
            progress.setProjectId(projectId);
            progress.setObjectType(dto.objectType());
            progress.setObjectName(dto.objectName());
            progress.setLastSyncValue(dto.lastSyncValue());
            progress.setCursorColumn(dto.cursorColumn());
            progress.setCursorStrategy(dto.cursorStrategy());
            progress.setResumableLoad(dto.resumableLoad());
            progress.setInitialLoadDone(dto.initialLoadDone());
            progress.setRowsSyncedTotal(dto.rowsSyncedTotal());
            progress.setLastSyncTime(dto.lastSyncTime());
            syncProgress.save(progress);
        }
        for (ConfigPackageDtos.CdcPositionDto dto : state.cdcPositions()) {
            Long projectId = projectIds.get(dto.projectId());
            if (projectId == null) {
                continue;
            }
            CdcPosition position = new CdcPosition();
            position.setProjectId(projectId);
            position.setBinlogFile(dto.binlogFile());
            position.setBinlogPos(dto.binlogPos());
            position.setGtidSet(dto.gtidSet());
            position.setServerId(dto.serverId());
            position.setScn(dto.scn());
            position.setLsn(dto.lsn());
            position.setSchemaSignatures(dto.schemaSignatures());
            position.setProjectionSignature(dto.projectionSignature());
            // Without this the restored position looks like a crashed mid-baseline row and the
            // consumer re-runs the whole full load, writing every row a second time.
            position.setBaselineDone(Boolean.TRUE.equals(dto.baselineDone())
                    && keepsRestoredBaselineFlag(dto.projectId(), projectId,
                            captureStrategyOf(projectSyncConfigs.get(dto.projectId()))));
            cdcPositions.save(position);
        }
        for (ConfigPackageDtos.MetadataSnapshotDto dto : state.metadataSnapshots()) {
            Long projectId = projectIds.get(dto.projectId());
            if (projectId == null) {
                continue;
            }
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setProjectId(projectId);
            snapshot.setObjectType(dto.objectType());
            snapshot.setObjectName(dto.objectName());
            snapshot.setSnapshotJson(dto.snapshotJson());
            snapshot.setContentHash(dto.contentHash());
            metadataSnapshots.save(snapshot);
        }
        for (ConfigPackageDtos.EtlCheckpointDto dto : state.etlCheckpoints()) {
            Long taskId = taskIds.get(dto.taskId());
            if (taskId == null) {
                continue;
            }
            EtlCheckpoint checkpoint = new EtlCheckpoint();
            checkpoint.setTaskId(taskId);
            checkpoint.setKeyType(dto.keyType());
            checkpoint.setKeyValue(dto.keyValue());
            etlCheckpoints.save(checkpoint);
        }
        for (ConfigPackageDtos.AlertStateDto dto : state.alertStates()) {
            AlertState alertState = new AlertState();
            alertState.setCategory(dto.category());
            Long projectId = dto.projectId();
            // Project 0 is "no project" (connection health) and stays 0; any other id has to
            // point at a restored project or the row is meaningless here.
            if (projectId != null && projectId != 0L) {
                projectId = projectIds.get(projectId);
                if (projectId == null) {
                    continue;
                }
            }
            alertState.setProjectId(projectId == null ? 0L : projectId);
            alertState.setObjectKey(dto.objectKey());
            alertState.setSubject(dto.subject());
            alertState.setActive(dto.active());
            alertState.setFirstSeenAt(dto.firstSeenAt());
            alertState.setLastNotifiedAt(dto.lastNotifiedAt());
            alertState.setLastEventAt(dto.lastEventAt());
            alertStates.save(alertState);
        }
    }

    /**
     * Whether a restored CDC position may keep its "baseline complete" flag.
     *
     * <p>Most positions are plain source coordinates — a binlog file/offset, an SCN, an LSN —
     * and mean the same thing on every instance. PostgreSQL's replication slot and YStream's
     * service are named after the project id ({@code prefix + id}), so when the import
     * renumbers the project the consumer asks the source for a resource name that has never
     * existed there. PostgreSQL treats a missing slot at a committed LSN as a purged stream
     * and parks the project until a human rebuilds the baseline; dropping the flag makes the
     * consumer create the fresh slot and run the baseline itself. YStream usually rebuilds on
     * its own, but the flag is dropped there too: an adopted service says nothing about a
     * position that arrived in a package. When the id is unchanged the resource name still
     * matches, so a genuine restart keeps resuming instead of paying for a full load.
     */
    static boolean keepsRestoredBaselineFlag(Long exportedProjectId, Long importedProjectId,
                                             CaptureStrategy strategy) {
        if (exportedProjectId == null || exportedProjectId.equals(importedProjectId)) {
            return true;
        }
        return strategy != CaptureStrategy.PG_LOGICAL_CDC
                && strategy != CaptureStrategy.YSTREAM_CDC;
    }

    /**
     * The project's capture strategy, read the way the runtime reads it: an absent or
     * unparseable config falls back to defaults, which is the polling path.
     */
    private CaptureStrategy captureStrategyOf(String syncConfig) {
        if (syncConfig == null || syncConfig.isBlank()) {
            return CaptureStrategy.POLLING;
        }
        try {
            return objectMapper.readValue(syncConfig, SyncConfig.class)
                    .getCaptureStrategyOrDefault();
        } catch (Exception e) {
            return CaptureStrategy.POLLING;
        }
    }

    /** Translation of every encrypted field, keyed by the package's own row ids. */
    private static final class Secrets {
        private final Map<Long, String> databasePassword = new HashMap<>();
        private final Map<Long, String> databaseMinerPassword = new HashMap<>();
        private final Map<Long, String> redisPassword = new HashMap<>();
        private final Map<Long, String> migrationPassword = new HashMap<>();
        private final Map<Long, String> migrationApiKey = new HashMap<>();
        private final Map<Long, String> aiApiKey = new HashMap<>();
        private String alertWebhook;
        private String oaSecret;
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("true") || normalized.equals("on")
                || normalized.equals("yes") || normalized.equals("1");
    }
}
