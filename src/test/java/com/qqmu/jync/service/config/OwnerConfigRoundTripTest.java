package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.qqmu.jync.dto.config.ConfigPackageDtos;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.repository.SystemSettingRepository;

/**
 * Ownership travels with a configuration package.
 *
 * <p>A migration restores the same resource tree on another installation; dropping the owner
 * would silently turn every operator's resources into administrator-only rows (a null owner is
 * the pre-v2.4.0 case), and the accounts that own them could no longer edit what they own.
 *
 * <p>The {@code seed.*} keys are machine state, not configuration: the local installation's
 * one-time markers must neither leave in a package nor be restored from one.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ownerconfig;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/ownerconfig-logs/jync.log"
})
class OwnerConfigRoundTripTest {

    private static final char[] PASSPHRASE = "owner passphrase".toCharArray();

    @Autowired private ConfigExportService exportService;
    @Autowired private ConfigImportService importService;
    @Autowired private ConfigPackageReader reader;
    @Autowired private DatabaseConfigRepository databaseConfigs;
    @Autowired private ProjectRepository projects;
    @Autowired private EtlTaskRepository etlTasks;
    @Autowired private EtlBatchRepository etlBatches;
    @Autowired private MigrationConnectionRepository migrationConnections;
    @Autowired private MigrationTaskRepository migrationTasks;
    @Autowired private RedisTargetRepository redisTargets;
    @Autowired private SystemSettingRepository systemSettings;
    @Autowired private AppUserRepository users;

    // The import's re-arm step starts schedulers; this test only cares about the package content.
    @MockitoBean private ConfigImportRuntime runtime;

    @BeforeEach
    void clean() {
        projects.deleteAll();
        etlTasks.deleteAll();
        etlBatches.deleteAll();
        migrationTasks.deleteAll();
        migrationConnections.deleteAll();
        redisTargets.deleteAll();
        databaseConfigs.deleteAll();
        systemSettings.deleteAll();
        users.deleteAll();
    }

    @Test
    void ownerColumnsSurviveAnExportAndImportRoundTrip() {
        users.save(user());
        DatabaseConfig source = databaseConfigs.save(database("src", ConnectionRole.SOURCE, "alice"));
        DatabaseConfig target = databaseConfigs.save(database("dst", ConnectionRole.TARGET, "alice"));
        databaseConfigs.save(database("legacy", ConnectionRole.SOURCE, null));
        projects.save(project("p-one", source, target, "bob"));
        RedisTarget redis = redisTargets.save(redisTarget("cache", "alice"));
        MigrationConnection migrationSource = migrationConnections.save(
                migrationConnection("es", MigrationConnectionKind.ELASTICSEARCH, "alice"));
        MigrationConnection migrationTarget = migrationConnections.save(
                migrationConnection("easy", MigrationConnectionKind.EASYSEARCH, "alice"));
        EtlBatch batch = etlBatches.save(batch("nightly", source, target, "bob"));
        etlTasks.save(etlTask("orders", source, target, batch, "alice"));
        migrationTasks.save(migrationTask("index-copy", migrationSource, migrationTarget, "bob"));

        byte[] pkg = exportService.build(PASSPHRASE, false).bytes();
        clean();

        importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(databaseConfigs.findAll())
                .extracting(DatabaseConfig::getName, DatabaseConfig::getOwnerUsername)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("src", "alice"),
                        org.assertj.core.groups.Tuple.tuple("dst", "alice"),
                        org.assertj.core.groups.Tuple.tuple("legacy", null));
        assertThat(projects.findAll()).singleElement()
                .extracting(Project::getOwnerUsername).isEqualTo("bob");
        assertThat(redisTargets.findAll()).singleElement()
                .extracting(RedisTarget::getOwnerUsername).isEqualTo("alice");
        assertThat(migrationConnections.findAll())
                .extracting(MigrationConnection::getOwnerUsername).containsOnly("alice");
        assertThat(etlBatches.findAll()).singleElement()
                .extracting(EtlBatch::getOwnerUsername).isEqualTo("bob");
        assertThat(etlTasks.findAll()).singleElement()
                .extracting(EtlTask::getOwnerUsername).isEqualTo("alice");
        assertThat(migrationTasks.findAll()).singleElement()
                .extracting(MigrationTask::getOwnerUsername).isEqualTo("bob");
    }

    @Test
    void seedKeysNeverEnterOrLeaveAPackage() {
        users.save(user());
        saveSetting("seed.local-ops", "true");
        saveSetting("update-check.enabled", "false");

        byte[] pkg = exportService.build(PASSPHRASE, false).bytes();
        List<String> packagedKeys = reader.read(pkg, PASSPHRASE).systemSettings().stream()
                .map(ConfigPackageDtos.SystemSettingDto::key)
                .toList();
        assertThat(packagedKeys).contains("update-check.enabled").noneMatch(
                key -> key.startsWith("seed."));

        clean();
        saveSetting("seed.local-ops", "false");

        importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(systemSettings.findById("seed.local-ops").orElseThrow().getValue())
                .as("the package never overwrites machine state").isEqualTo("false");
        assertThat(systemSettings.findById("update-check.enabled").orElseThrow().getValue())
                .isEqualTo("false");
    }

    // --- helpers ------------------------------------------------------------------------

    private void saveSetting(String key, String value) {
        SystemSetting row = new SystemSetting();
        row.setKey(key);
        row.setValue(value);
        row.setUpdatedAt(Instant.now());
        systemSettings.save(row);
    }

    private static AppUser user() {
        AppUser user = new AppUser();
        user.setUsername("admin");
        user.setPasswordHash("hash-admin");
        user.setRole(UserRole.ADMIN);
        return user;
    }

    private static DatabaseConfig database(String name, ConnectionRole role, String owner) {
        DatabaseConfig db = new DatabaseConfig();
        db.setName(name);
        db.setType(DatabaseType.MYSQL);
        db.setRole(role);
        db.setHost("localhost");
        db.setPort(3306);
        db.setDatabaseName(name);
        db.setUsername("app");
        db.setOwnerUsername(owner);
        return db;
    }

    private static Project project(String name, DatabaseConfig source, DatabaseConfig target,
                                   String owner) {
        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setOwnerUsername(owner);
        return project;
    }

    private static RedisTarget redisTarget(String name, String owner) {
        RedisTarget target = new RedisTarget();
        target.setName(name);
        target.setHost("localhost");
        target.setPort(6379);
        target.setDatabaseIndex(0);
        target.setConnectTimeoutMs(5000);
        target.setCommandTimeoutMs(5000);
        target.setOwnerUsername(owner);
        return target;
    }

    private static MigrationConnection migrationConnection(String name,
                                                           MigrationConnectionKind kind,
                                                           String owner) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName(name);
        connection.setKind(kind);
        connection.setBaseUrl("http://localhost:9200");
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        connection.setVerifyTls(true);
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        connection.setOwnerUsername(owner);
        return connection;
    }

    private static EtlBatch batch(String name, DatabaseConfig source, DatabaseConfig target,
                                  String owner) {
        EtlBatch batch = new EtlBatch();
        batch.setName(name);
        batch.setSourceDbId(source.getId());
        batch.setTargetDbId(target.getId());
        batch.setOwnerUsername(owner);
        return batch;
    }

    private static EtlTask etlTask(String name, DatabaseConfig source, DatabaseConfig target,
                                   EtlBatch batch, String owner) {
        EtlTask task = new EtlTask();
        task.setName(name);
        task.setSourceDbId(source.getId());
        task.setTargetDbId(target.getId());
        task.setBatchId(batch.getId());
        task.setSourceSql("SELECT 1");
        task.setTargetTable(name);
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        task.setOwnerUsername(owner);
        return task;
    }

    private static MigrationTask migrationTask(String name, MigrationConnection source,
                                               MigrationConnection target, String owner) {
        MigrationTask task = new MigrationTask();
        task.setName(name);
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setOptionsJson("{}");
        task.setOwnerUsername(owner);
        return task;
    }
}
