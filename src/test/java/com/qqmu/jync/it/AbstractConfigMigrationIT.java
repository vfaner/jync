package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.ObjectType;
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
import com.qqmu.jync.service.config.ConfigExportService;
import com.qqmu.jync.service.config.ConfigImportService;
import com.qqmu.jync.service.settings.SystemSettingKey;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Export from one metadata database, rotate the key, import into a fresh one.
 *
 * <p>This is the actual migration an administrator performs: H2 → MySQL/PostgreSQL is a different
 * database and, in practice, a different installation with its own {@code sync.crypto-*} values.
 * The package carries the exporter's key in its sealed envelope, so every {@code enc:} value must
 * be re-encrypted with the target's key during the import — otherwise the imported connections
 * look fine in the list and fail on first use.
 *
 * <p>The assertions walk the same order an operator would: counts, foreign keys, then that each
 * secret decrypts with the <em>new</em> key.
 */
@SpringBootTest(properties = {
        "sync.ai.enabled=false",
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false"
})
abstract class AbstractConfigMigrationIT {

    private static final char[] PASSPHRASE = "migration passphrase".toCharArray();
    private static final String TARGET_CRYPTO_PASSWORD = "target-installation-key";
    private static final String TARGET_CRYPTO_SALT = "0f1e2d3c4b5a6978";

    @Autowired
    private ConfigExportService exportService;
    @Autowired
    private ConfigImportService importService;
    @Autowired
    private CryptoUtil cryptoUtil;

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

    @Test
    void aPackageSurvivesADifferentDatabaseAndADifferentKey() {
        long changeLogsBefore = changeLogs.count();
        seed();

        byte[] pkg = exportService.build(PASSPHRASE, true).bytes();
        wipeConfiguration();
        users.findByUsername("ops").ifPresent(users::delete);
        // The new installation has its own key. Every ciphertext in the package was written
        // with the old one; nothing short of re-encryption makes it readable here.
        cryptoUtil.rotateTo(TARGET_CRYPTO_PASSWORD, TARGET_CRYPTO_SALT);
        assertThat(cryptoUtil.isUsingDefaultKey()).isFalse();

        ConfigImportService.ImportResult result =
                importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(result.databases()).isEqualTo(2);
        assertThat(result.redisTargets()).isEqualTo(1);
        assertThat(result.migrationConnections()).isEqualTo(2);
        assertThat(result.projects()).isEqualTo(1);
        assertThat(result.etlTasks()).isEqualTo(1);
        assertThat(result.migrationTasks()).isEqualTo(1);
        assertThat(result.aiProviders()).isEqualTo(1);
        assertThat(result.usersInserted()).isEqualTo(1);
        assertThat(result.stateImported()).isTrue();
        assertThat(result.httpsReset()).isFalse();
        assertThat(result.warningKeys()).doesNotContain("warn.config.import.httpsReset");

        // --- rows and references ---------------------------------------------------------
        List<DatabaseConfig> restoredDatabases = databaseConfigs.findAll();
        assertThat(restoredDatabases).extracting(DatabaseConfig::getName)
                .containsExactlyInAnyOrder("src", "dst");
        Set<Long> newDatabaseIds = restoredDatabases.stream()
                .map(DatabaseConfig::getId).collect(java.util.stream.Collectors.toSet());

        Project project = projects.findAll().get(0);
        assertThat(newDatabaseIds).contains(project.getSourceDbId(), project.getTargetDbId());
        assertThat(project.getSyncConfig()).isEqualTo(largeConfig());
        assertThat(project.getEnabled()).isFalse();

        EtlTask etl = etlTasks.findAll().get(0);
        assertThat(newDatabaseIds).contains(etl.getSourceDbId(), etl.getTargetDbId());
        assertThat(etl.getSourceSql()).isEqualTo("select 1");

        List<MigrationConnection> connections = migrationConnections.findAll();
        Set<Long> connectionIds = connections.stream()
                .map(MigrationConnection::getId).collect(java.util.stream.Collectors.toSet());
        MigrationTask migration = migrationTasks.findAll().get(0);
        assertThat(connectionIds)
                .contains(migration.getSourceConnectionId(), migration.getTargetConnectionId());
        assertThat(migration.getType()).isEqualTo(MigrationType.ES_TO_EASYSEARCH);

        // --- secrets decrypt with the target key -----------------------------------------
        DatabaseConfig src = restoredDatabases.stream()
                .filter(db -> db.getName().equals("src")).findFirst().orElseThrow();
        assertThat(cryptoUtil.decrypt(src.getPassword())).isEqualTo("db-secret");
        assertThat(redisTargets.findAll().get(0).getPasswordEnc()).startsWith(CryptoUtil.PREFIX);
        assertThat(cryptoUtil.decrypt(redisTargets.findAll().get(0).getPasswordEnc()))
                .isEqualTo("redis-secret");
        MigrationConnection es = connections.stream()
                .filter(c -> c.getKind() == MigrationConnectionKind.ELASTICSEARCH)
                .findFirst().orElseThrow();
        assertThat(cryptoUtil.decrypt(es.getPasswordEnc())).isEqualTo("es-secret");
        assertThat(cryptoUtil.decrypt(aiProviders.findAll().get(0).getApiKey()))
                .isEqualTo("ai-secret");

        // --- singleton, settings and state ------------------------------------------------
        AlertConfig alert = alertConfig.findById(1L).orElseThrow();
        assertThat(cryptoUtil.decrypt(alert.getWebhookUrl()))
                .isEqualTo("https://hooks.example.com/secret");
        assertThat(oaAuthConfig.findById(1L).orElseThrow().getMode())
                .isEqualTo(OaAuthConfig.Mode.PASSWORD);
        assertThat(systemSettings.findById(SystemSettingKey.SECURITY_REQUIRE_HTTPS.key())
                .orElseThrow().getValue()).isEqualTo("true");

        SyncProgress progress = syncProgress.findAll().get(0);
        assertThat(progress.getProjectId()).isEqualTo(project.getId());

        assertThat(users.findByUsername("ops")).isPresent();
        assertThat(changeLogs.count()).as("history does not travel with a package")
                .isEqualTo(changeLogsBefore);
    }

    // --- fixtures ------------------------------------------------------------------------

    private static final String LARGE_CONFIG = "{\"tables\":[\"" + "t".repeat(40_000) + "\"]}";

    private static String largeConfig() {
        return LARGE_CONFIG;
    }

    private long seed() {
        DatabaseConfig source = database("src", "db-secret");
        DatabaseConfig target = database("dst", "db-secret-2");

        RedisTarget redis = new RedisTarget();
        redis.setName("cache");
        redis.setHost("redis.internal");
        redis.setPasswordEnc(cryptoUtil.encrypt("redis-secret"));
        redisTargets.save(redis);

        MigrationConnection es = new MigrationConnection();
        es.setName("es-source");
        es.setKind(MigrationConnectionKind.ELASTICSEARCH);
        es.setBaseUrl("https://es.internal:9200");
        es.setAuthType(MigrationConnectionAuthType.BASIC);
        es.setUsername("elastic");
        es.setPasswordEnc(cryptoUtil.encrypt("es-secret"));
        migrationConnections.save(es);

        MigrationConnection easy = new MigrationConnection();
        easy.setName("easysearch-target");
        easy.setKind(MigrationConnectionKind.EASYSEARCH);
        easy.setBaseUrl("https://easy.internal:9200");
        migrationConnections.save(easy);

        Project project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(largeConfig());
        project.setEnabled(false);
        projects.save(project);

        SyncProgress progress = new SyncProgress();
        progress.setProjectId(project.getId());
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("orders");
        syncProgress.save(progress);

        EtlTask etl = new EtlTask();
        etl.setName("nightly");
        etl.setSourceDbId(source.getId());
        etl.setTargetDbId(target.getId());
        etl.setSourceSql("select 1");
        etl.setTargetTable("orders_copy");
        etlTasks.save(etl);

        MigrationTask migration = new MigrationTask();
        migration.setName("es-copy");
        migration.setType(MigrationType.ES_TO_EASYSEARCH);
        migration.setSourceConnectionId(es.getId());
        migration.setTargetConnectionId(easy.getId());
        migration.setOptionsJson("{\"indices\":[\"orders-*\"]}");
        migrationTasks.save(migration);

        AiProvider ai = new AiProvider();
        ai.setName("assistant");
        ai.setBaseUrl("https://ai.example.com");
        ai.setApiKey(cryptoUtil.encrypt("ai-secret"));
        aiProviders.save(ai);

        AlertConfig alert = new AlertConfig();
        alert.setId(1L);
        alert.setEnabled(true);
        alert.setWebhookUrl(cryptoUtil.encrypt("https://hooks.example.com/secret"));
        alertConfig.save(alert);

        OaAuthConfig oa = new OaAuthConfig();
        oa.setId(1L);
        oa.setMode(OaAuthConfig.Mode.PASSWORD);
        oaAuthConfig.save(oa);

        AppUser ops = new AppUser();
        ops.setUsername("ops");
        ops.setPasswordHash("hash-ops");
        ops.setRole(UserRole.ADMIN);
        users.save(ops);

        SystemSetting https = new SystemSetting();
        https.setKey(SystemSettingKey.SECURITY_REQUIRE_HTTPS.key());
        https.setValue("true");
        https.setUpdatedAt(Instant.now());
        systemSettings.save(https);

        return project.getId();
    }

    private DatabaseConfig database(String name, String password) {
        DatabaseConfig db = new DatabaseConfig();
        db.setName(name);
        db.setType(DatabaseType.MYSQL);
        db.setHost("localhost");
        db.setPort(3306);
        db.setDatabaseName(name);
        db.setUsername("app");
        db.setPassword(cryptoUtil.encrypt(password));
        return databaseConfigs.save(db);
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
    }
}
