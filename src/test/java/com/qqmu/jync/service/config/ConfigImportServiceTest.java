package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlCheckpoint;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.ObjectType;
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
import com.qqmu.jync.repository.ChangeLogRepository;
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
import com.qqmu.jync.util.CryptoUtil;

/**
 * Importing a configuration package into a freshly created metadata database.
 *
 * <p>Two rules shape every test here. The target must be empty — an import into a database that
 * already holds projects would either duplicate them or silently adopt half of each — and the
 * ids in the package are the exporter's, so every write must insert fresh rows and remap the
 * references to them.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:configimport;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/configimport-logs/jync.log"
})
class ConfigImportServiceTest {

    private static final char[] PASSPHRASE = "import passphrase".toCharArray();

    @Autowired
    private ConfigExportService exportService;
    @Autowired
    private ConfigImportService importService;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private SyncProperties syncProperties;
    @Autowired
    private com.qqmu.jync.service.settings.SystemSettingService settingService;
    // Mocked so an import in this test does not start real schedulers; the re-arm behaviour
    // itself is covered by ConfigImportRuntimeTest.
    @MockitoBean
    private ConfigImportRuntime runtime;

    @Autowired
    private DatabaseConfigRepository databaseConfigs;
    @Autowired
    private RedisTargetRepository redisTargets;
    @Autowired
    private MigrationConnectionRepository migrationConnections;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private EtlBatchRepository etlBatches;
    @Autowired
    private EtlTaskRepository etlTasks;
    @Autowired
    private MigrationTaskRepository migrationTasks;
    @Autowired
    private AiProviderRepository aiProviders;
    @Autowired
    private AlertConfigRepository alertConfig;
    @Autowired
    private OaAuthConfigRepository oaAuthConfig;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private SystemSettingRepository systemSettings;
    @Autowired
    private SyncProgressRepository syncProgress;
    @Autowired
    private CdcPositionRepository cdcPositions;
    @Autowired
    private MetadataSnapshotRepository metadataSnapshots;
    @Autowired
    private EtlCheckpointRepository etlCheckpoints;
    @Autowired
    private AlertStateRepository alertStates;
    @Autowired
    private ChangeLogRepository changeLogs;

    @BeforeEach
    void clean() {
        wipeConfiguration();
        users.deleteAll();
    }

    private void wipeConfiguration() {
        alertStates.deleteAll();
        metadataSnapshots.deleteAll();
        cdcPositions.deleteAll();
        syncProgress.deleteAll();
        etlCheckpoints.deleteAll();
        migrationTasks.deleteAll();
        etlTasks.deleteAll();
        etlBatches.deleteAll();
        projects.deleteAll();
        redisTargets.deleteAll();
        migrationConnections.deleteAll();
        databaseConfigs.deleteAll();
        aiProviders.deleteAll();
        alertConfig.deleteAll();
        oaAuthConfig.deleteAll();
        systemSettings.deleteAll();
        changeLogs.deleteAll();
    }

    private ConfigExportService.ConfigPackage export(boolean includeState) {
        return exportService.build(PASSPHRASE, includeState);
    }

    private void seedAdmin() {
        users.save(user("admin", "hash-admin", UserRole.ADMIN));
    }

    @Test
    @DisplayName("a package restores into an empty database")
    void aPackageRestoresIntoAnEmptyDatabase() {
        seedAdmin();
        DatabaseConfig source = databaseConfigs.save(database("src"));
        DatabaseConfig target = databaseConfigs.save(database("dst"));
        Project project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig("{\"tables\":[]}");
        project.setEnabled(true);
        projects.save(project);
        RedisTarget redis = new RedisTarget();
        redis.setName("cache");
        redis.setHost("redis.internal");
        redisTargets.save(redis);
        AiProvider ai = new AiProvider();
        ai.setName("assistant");
        ai.setBaseUrl("https://ai.example.com");
        ai.setApiKey(cryptoUtil.encrypt("ai-secret"));
        aiProviders.save(ai);

        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        users.deleteAll();

        ConfigImportService.ImportResult result =
                importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(result.databases()).isEqualTo(2);
        assertThat(result.projects()).isEqualTo(1);
        assertThat(result.redisTargets()).isEqualTo(1);
        assertThat(result.aiProviders()).isEqualTo(1);
        assertThat(result.usersInserted()).isEqualTo(1);
        assertThat(databaseConfigs.findAll()).extracting(DatabaseConfig::getName)
                .containsExactlyInAnyOrder("src", "dst");
        assertThat(projects.findAll()).singleElement()
                .satisfies(restored -> {
                    assertThat(restored.getName()).isEqualTo("orders");
                    assertThat(restored.getSyncConfig()).isEqualTo("{\"tables\":[]}");
                    assertThat(restored.getEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("an import invalidates the settings cache on this node")
    void anImportInvalidatesTheSettingsCacheOnThisNode() {
        seedAdmin();
        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        users.deleteAll();

        long before = settingService.revision();

        importService.importPackage(pkg, PASSPHRASE, true);

        // The package carries system_setting rows (including force-HTTPS). Writing them
        // straight through the repository used to leave the resolver's revision untouched,
        // so this node kept enforcing the old value for a whole TTL.
        assertThat(settingService.revision()).isGreaterThan(before);
    }

    @Test
    @DisplayName("references point at the ids this database assigned, not the package's")
    void referencesAreRemapped() {
        seedAdmin();
        DatabaseConfig source = databaseConfigs.save(database("src"));
        DatabaseConfig target = databaseConfigs.save(database("dst"));
        Project project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig("{}");
        projects.save(project);
        long oldSourceId = source.getId();

        byte[] pkg = export(false).bytes();
        wipeConfiguration();

        importService.importPackage(pkg, PASSPHRASE, true);

        Long newSourceId = databaseConfigs.findAll().stream()
                .filter(db -> "src".equals(db.getName())).findFirst().orElseThrow().getId();
        Project restored = projects.findAll().get(0);
        assertThat(restored.getSourceDbId()).isEqualTo(newSourceId);
        assertThat(newSourceId).isNotEqualTo(oldSourceId);
        assertThat(databaseConfigs.findById(restored.getTargetDbId())).isPresent();
    }

    @Test
    @DisplayName("a database that already holds configuration refuses the package")
    void aPopulatedDatabaseRefusesTheImport() {
        seedAdmin();
        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        AiProvider existing = new AiProvider();
        existing.setName("keep-me");
        aiProviders.save(existing);

        assertThatThrownBy(() -> importService.importPackage(pkg, PASSPHRASE, true))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.notEmpty");

        assertThat(aiProviders.findAll()).extracting(AiProvider::getName)
                .containsExactly("keep-me");
        // The user seeded for the export was never removed, and the refused import added none.
        assertThat(users.findAll()).extracting(AppUser::getUsername).containsExactly("admin");
    }

    @Test
    @DisplayName("a dangling reference aborts the import before anything is written")
    void aMissingReferenceRollsBackEverything() {
        seedAdmin();
        databaseConfigs.save(database("src"));
        Project project = new Project();
        project.setName("broken");
        project.setSourceDbId(999_999L);
        project.setTargetDbId(999_999L);
        project.setSyncConfig("{}");
        projects.save(project);

        byte[] pkg = export(false).bytes();
        wipeConfiguration();

        assertThatThrownBy(() -> importService.importPackage(pkg, PASSPHRASE, true))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.referenceMissing");

        assertThat(databaseConfigs.findAll()).isEmpty();
        assertThat(projects.findAll()).isEmpty();
    }

    @Test
    @DisplayName("users merge by username and the target keeps its own credentials")
    void usersMergeByUsername() {
        DatabaseConfig source = databaseConfigs.save(database("src"));
        etlTask(source);
        users.save(user("ops", "source-hash", UserRole.VIEWER));
        users.save(user("new", "new-hash", UserRole.VIEWER));

        byte[] pkg = export(false).bytes();
        // The target survives the wipe: only configuration is cleared before an import.
        wipeConfiguration();
        users.deleteAll();
        users.save(user("ops", "target-hash", UserRole.ADMIN));

        ConfigImportService.ImportResult result =
                importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(result.usersKept()).isEqualTo(1);
        assertThat(result.usersInserted()).isEqualTo(1);
        assertThat(users.findByUsername("ops")).get()
                .satisfies(kept -> {
                    assertThat(kept.getPasswordHash()).isEqualTo("target-hash");
                    assertThat(kept.getRole()).isEqualTo(UserRole.ADMIN);
                });
        assertThat(users.findByUsername("new")).isPresent();
    }

    @Test
    @DisplayName("an import that would leave no administrator is rolled back")
    void anImportMayNotLeaveWithoutAnAdmin() {
        DatabaseConfig source = databaseConfigs.save(database("src"));
        etlTask(source);
        users.save(user("viewer", "hash", UserRole.VIEWER));

        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        users.deleteAll();

        assertThatThrownBy(() -> importService.importPackage(pkg, PASSPHRASE, true))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.noAdmin");

        assertThat(databaseConfigs.findAll()).isEmpty();
        assertThat(users.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the singleton pages are overwritten, not duplicated")
    void singletonsAreOverwritten() {
        seedAdmin();
        AlertConfig config = new AlertConfig();
        config.setId(1L);
        config.setEnabled(true);
        config.setWebhookUrl(cryptoUtil.encrypt("https://hook.example.com/one"));
        alertConfig.save(config);

        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        AlertConfig existing = new AlertConfig();
        existing.setId(1L);
        existing.setEnabled(false);
        alertConfig.save(existing);

        importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(alertConfig.findAll()).singleElement()
                .satisfies(restored -> {
                    assertThat(restored.isEnabled()).isTrue();
                    assertThat(cryptoUtil.decrypt(restored.getWebhookUrl()))
                            .isEqualTo("https://hook.example.com/one");
                });
    }

    @Test
    @DisplayName("a forced-HTTPS setting survives only if the import itself arrived over HTTPS")
    void httpsIsResetWhenTheImportIsPlaintext() {
        seedAdmin();
        systemSettings.save(setting("security.require-https", "true"));

        byte[] pkg = export(false).bytes();
        wipeConfiguration();

        ConfigImportService.ImportResult plaintext =
                importService.importPackage(pkg, PASSPHRASE, false);

        assertThat(plaintext.httpsReset()).isTrue();
        assertThat(plaintext.warningKeys()).contains("warn.config.import.httpsReset");
        assertThat(systemSettings.findById("security.require-https")).get()
                .satisfies(setting -> assertThat(setting.getValue()).isEqualTo("false"));

        wipeConfiguration();
        systemSettings.save(setting("security.require-https", "true"));
        byte[] again = export(false).bytes();
        wipeConfiguration();

        ConfigImportService.ImportResult secure =
                importService.importPackage(again, PASSPHRASE, true);

        assertThat(secure.httpsReset()).isFalse();
        assertThat(systemSettings.findById("security.require-https")).get()
                .satisfies(setting -> assertThat(setting.getValue()).isEqualTo("true"));
    }

    @Test
    @DisplayName("sync state follows only when the package carries it")
    void stateFollowsTheFlag() {
        seedAdmin();
        DatabaseConfig source = databaseConfigs.save(database("src"));
        Project project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setSyncConfig("{}");
        projects.save(project);
        SyncProgress progress = new SyncProgress();
        progress.setProjectId(project.getId());
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("orders");
        syncProgress.save(progress);

        ConfigExportService.ConfigPackage withoutState = export(false);
        wipeConfiguration();
        assertThat(importService.importPackage(withoutState.bytes(), PASSPHRASE, true).stateImported())
                .isFalse();
        assertThat(syncProgress.findAll()).isEmpty();

        wipeConfiguration();
        source = databaseConfigs.save(database("src"));
        project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setSyncConfig("{}");
        projects.save(project);
        progress = new SyncProgress();
        progress.setProjectId(project.getId());
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("orders");
        syncProgress.save(progress);
        EtlTask task = etlTask(source);
        EtlCheckpoint checkpoint = new EtlCheckpoint();
        checkpoint.setTaskId(task.getId());
        checkpoint.setKeyType(0);
        checkpoint.setKeyValue("42");
        etlCheckpoints.save(checkpoint);

        byte[] withState = export(true).bytes();
        wipeConfiguration();

        ConfigImportService.ImportResult result =
                importService.importPackage(withState, PASSPHRASE, true);

        assertThat(result.stateImported()).isTrue();
        Long newProjectId = projects.findAll().get(0).getId();
        assertThat(syncProgress.findAll()).singleElement()
                .satisfies(restored -> assertThat(restored.getProjectId()).isEqualTo(newProjectId));
        Long newTaskId = etlTasks.findAll().get(0).getId();
        assertThat(etlCheckpoints.findAll()).singleElement()
                .satisfies(restored -> assertThat(restored.getTaskId()).isEqualTo(newTaskId));
    }

    @Test
    @DisplayName("a restored CDC position keeps its baseline flag")
    void aRestoredCdcPositionKeepsItsBaselineFlag() {
        // The flag is what tells a restart that the full baseline already ran. Losing it on
        // import makes every consumer re-run the whole baseline and write its rows a second time.
        seedAdmin();
        DatabaseConfig source = databaseConfigs.save(database("src"));
        Project project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setSyncConfig("{}");
        projects.save(project);
        CdcPosition position = new CdcPosition();
        position.setProjectId(project.getId());
        position.setBinlogFile("mysql-bin.000042");
        position.setBinlogPos(1234L);
        position.setBaselineDone(true);
        cdcPositions.save(position);

        byte[] pkg = export(true).bytes();
        wipeConfiguration();

        importService.importPackage(pkg, PASSPHRASE, true);

        Long newProjectId = projects.findAll().get(0).getId();
        assertThat(cdcPositions.findAll()).singleElement().satisfies(restored -> {
            assertThat(restored.getProjectId()).isEqualTo(newProjectId);
            assertThat(restored.getBinlogFile()).isEqualTo("mysql-bin.000042");
            assertThat(restored.getBinlogPos()).isEqualTo(1234L);
            assertThat(restored.isBaselineDone()).isTrue();
        });
    }

    @Test
    @DisplayName("a renumbered project drops the baseline flag only where the source resource is named after it")
    void aRenumberedProjectDropsTheBaselineFlagForIdNamedResources() {
        // PostgreSQL's replication slot name and YStream's service name embed the project
        // id. Import renumbers projects, so the consumer would ask the source for a slot
        // name it never had; PgLogicalCdcConsumer treats a missing slot at a committed LSN
        // as a purged stream and parks the project for a manual rebuild. Dropping the flag
        // makes it create the new slot and run the baseline instead. Binlog/SCN/LSN
        // positions are plain source coordinates, so they stay valid across the renumber
        // and re-running their baseline is exactly the full load this restore avoids.
        seedAdmin();
        DatabaseConfig source = databaseConfigs.save(database("src"));
        long pgExportId = savePositionedProject(source, "orders-pg",
                "{\"captureStrategy\":\"PG_LOGICAL_CDC\"}",
                position -> position.setLsn("0/16B3748"));
        long binlogExportId = savePositionedProject(source, "orders-binlog",
                "{\"captureStrategy\":\"BINLOG_CDC\"}", position -> {
                    position.setBinlogFile("mysql-bin.000042");
                    position.setBinlogPos(1234L);
                });
        long ystreamExportId = savePositionedProject(source, "orders-ystream",
                "{\"captureStrategy\":\"YSTREAM_CDC\"}",
                position -> position.setLsn("0/2A"));

        byte[] pkg = export(true).bytes();
        wipeConfiguration();

        importService.importPackage(pkg, PASSPHRASE, true);

        // The premise of the test: the import really did assign different ids.
        assertThat(projects.findAll()).extracting(Project::getId)
                .doesNotContain(pgExportId, binlogExportId, ystreamExportId);

        Map<String, CdcPosition> byProjectName = new HashMap<>();
        Map<Long, String> names = projects.findAll().stream()
                .collect(Collectors.toMap(Project::getId, Project::getName));
        for (CdcPosition restored : cdcPositions.findAll()) {
            byProjectName.put(names.get(restored.getProjectId()), restored);
        }

        assertThat(byProjectName.get("orders-pg").isBaselineDone())
                .as("the restored PG position cannot belong to the new slot name").isFalse();
        assertThat(byProjectName.get("orders-pg").getLsn()).isEqualTo("0/16B3748");
        assertThat(byProjectName.get("orders-ystream").isBaselineDone())
                .as("the YStream service name embeds the project id too").isFalse();
        assertThat(byProjectName.get("orders-binlog").isBaselineDone())
                .as("a binlog position is portable across instances").isTrue();
        assertThat(byProjectName.get("orders-binlog").getBinlogFile())
                .isEqualTo("mysql-bin.000042");
    }

    @Test
    @DisplayName("the change log of the exporting database is not part of the package")
    void theChangeLogStaysOutOfTheImport() {
        seedAdmin();
        ChangeLog log = new ChangeLog();
        log.setProjectId(1L);
        log.setObjectType(ObjectType.TABLE);
        log.setChangeType(ChangeType.INSERT);
        log.setOccurredAt(Instant.now());
        changeLogs.save(log);

        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        ChangeLog kept = new ChangeLog();
        kept.setProjectId(2L);
        kept.setObjectType(ObjectType.TABLE);
        kept.setChangeType(ChangeType.UPDATE);
        kept.setOccurredAt(Instant.now());
        changeLogs.save(kept);

        importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(changeLogs.findAll()).singleElement()
                .satisfies(remaining -> assertThat(remaining.getProjectId()).isEqualTo(2L));
    }

    @Test
    @DisplayName("secrets are re-encrypted with this deployment's key")
    void secretsAreTranslatedToTheTargetKey() {
        String sourcePassword = syncProperties.getCryptoPassword();
        String sourceSalt = syncProperties.getCryptoSalt();
        try {
            DatabaseConfig db = database("src");
            db.setPassword(cryptoUtil.encrypt("db-secret"));
            databaseConfigs.save(db);
            seedAdmin();

            byte[] pkg = export(false).bytes();
            wipeConfiguration();
            users.deleteAll();

            cryptoUtil.rotateTo("target-key-material", "fedcba9876543210");
            importService.importPackage(pkg, PASSPHRASE, true);

            DatabaseConfig restored = databaseConfigs.findAll().get(0);
            assertThat(restored.getPassword()).isNotEqualTo("db-secret");
            assertThat(cryptoUtil.decrypt(restored.getPassword())).isEqualTo("db-secret");
        } finally {
            cryptoUtil.rotateTo(sourcePassword, sourceSalt);
        }
    }

    @Test
    @DisplayName("a wrong passphrase and a foreign file are refused by name")
    void badPackagesAreRefused() {
        assertThatThrownBy(() -> importService.importPackage(
                new byte[] {'P', 'K', 3, 4}, PASSPHRASE, true))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badFormat");

        seedAdmin();
        byte[] pkg = export(false).bytes();
        assertThatThrownBy(() -> importService.importPackage(pkg, "wrong passphrase".toCharArray(), true))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badPassphrase");
    }

    @Test
    @DisplayName("a package written with the shipped default key says so in its warnings")
    void theDefaultSourceKeyIsCalledOut() {
        seedAdmin();
        ConfigImportService.ImportResult result =
                importService.importPackage(export(false).bytes(), PASSPHRASE, true);

        if (cryptoUtil.isUsingDefaultKey()) {
            assertThat(result.warningKeys()).contains("warn.config.import.defaultSourceKey");
        }
    }

    @Test
    @DisplayName("a committed import re-arms the running process")
    void aCommittedImportRearmsTheRuntime() {
        seedAdmin();
        byte[] pkg = export(false).bytes();
        wipeConfiguration();
        users.deleteAll();

        importService.importPackage(pkg, PASSPHRASE, true);

        verify(runtime).rearm();
    }

    @Test
    @DisplayName("a refused import leaves the running process alone")
    void aRefusedImportDoesNotRearmTheRuntime() {
        seedAdmin();
        databaseConfigs.save(database("existing"));
        byte[] pkg = export(false).bytes();

        assertThatThrownBy(() -> importService.importPackage(pkg, PASSPHRASE, true))
                .isInstanceOf(ConfigPackageException.class);

        verify(runtime, never()).rearm();
    }

    // --- fixtures ------------------------------------------------------------------------

    private DatabaseConfig database(String name) {
        DatabaseConfig db = new DatabaseConfig();
        db.setName(name);
        db.setType(DatabaseType.MYSQL);
        db.setHost("localhost");
        db.setPort(3306);
        db.setDatabaseName(name);
        db.setUsername("app");
        db.setPassword(cryptoUtil.encrypt("plain-password"));
        return db;
    }

    /** Saves a project with the given stored config plus a baseline-complete CDC position. */
    private long savePositionedProject(DatabaseConfig source, String name, String syncConfig,
                                       java.util.function.Consumer<CdcPosition> positionParts) {
        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(source.getId());
        project.setSyncConfig(syncConfig);
        projects.save(project);
        CdcPosition position = new CdcPosition();
        position.setProjectId(project.getId());
        position.setBaselineDone(true);
        positionParts.accept(position);
        cdcPositions.save(position);
        return project.getId();
    }

    private EtlTask etlTask(DatabaseConfig source) {
        EtlTask task = new EtlTask();
        task.setName("nightly");
        task.setSourceDbId(source.getId());
        task.setTargetDbId(source.getId());
        task.setSourceSql("select 1");
        task.setTargetTable("t");
        return etlTasks.save(task);
    }

    private static AppUser user(String name, String hash, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(name);
        user.setPasswordHash(hash);
        user.setRole(role);
        return user;
    }

    private static SystemSetting setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setKey(key);
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        return setting;
    }
}
