package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.AiProviderRepository;
import com.qqmu.jync.repository.AlertConfigRepository;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.repository.OaAuthConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.repository.SystemSettingRepository;

/**
 * What an exported package contains.
 *
 * <p>The package exists to survive a change of metadata database, so it carries configuration and
 * (optionally) the cursors that make an interrupted sync resumable — never the append-only logs,
 * audit trail, metric samples or run history, which belong to the database that recorded them.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:configexport;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/configexport-logs/jync.log"
})
class ConfigExportServiceTest {

    private static final char[] PASSPHRASE = "export passphrase".toCharArray();

    private static final List<String> CONFIG_ENTRIES = List.of(
            "config/ai-providers.json",
            "config/alert-config.json",
            "config/app-users.json",
            "config/database-configs.json",
            "config/etl-batches.json",
            "config/etl-tasks.json",
            "config/migration-connections.json",
            "config/migration-tasks.json",
            "config/oa-auth-config.json",
            "config/projects.json",
            "config/redis-targets.json",
            "config/system-settings.json",
            "manifest.json",
            "meta.json");

    private static final List<String> STATE_ENTRIES = List.of(
            "state/alert-states.json",
            "state/cdc-positions.json",
            "state/etl-checkpoints.json",
            "state/metadata-snapshots.json",
            "state/sync-progress.json");

    @Autowired
    private ConfigExportService exportService;
    @Autowired
    private ConfigPackageCrypto packageCrypto;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SyncProperties syncProperties;

    @Autowired
    private DatabaseConfigRepository databaseConfigs;
    @Autowired
    private RedisTargetRepository redisTargets;
    @Autowired
    private ProjectRepository projects;
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
    private ChangeLogRepository changeLogs;

    @BeforeEach
    void clean() {
        syncProgress.deleteAll();
        changeLogs.deleteAll();
        migrationTasks.deleteAll();
        etlTasks.deleteAll();
        projects.deleteAll();
        redisTargets.deleteAll();
        databaseConfigs.deleteAll();
        aiProviders.deleteAll();
        alertConfig.deleteAll();
        oaAuthConfig.deleteAll();
        systemSettings.deleteAll();
        // The seeding service added admin/view when the context started; these tests count users.
        users.deleteAll();
    }

    private ConfigExportService.ConfigPackage export(boolean includeState) {
        return exportService.build(PASSPHRASE, includeState);
    }

    private Map<String, byte[]> entriesOf(ConfigExportService.ConfigPackage pkg) throws IOException {
        return unzip(packageCrypto.decrypt(pkg.bytes(), PASSPHRASE).zip());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private JsonNode json(Map<String, byte[]> entries, String name) throws IOException {
        return objectMapper.readTree(entries.get(name));
    }

    @Test
    @DisplayName("an export carries exactly the configuration entries")
    void theEntrySetIsExact() throws IOException {
        ConfigExportService.ConfigPackage pkg = export(false);

        assertThat(entriesOf(pkg).keySet()).containsExactlyInAnyOrderElementsOf(CONFIG_ENTRIES);
        assertThat(pkg.filename()).matches("jync-config-\\d{8}-\\d{6}\\.jync");
        assertThat(pkg.entryCount()).isEqualTo(CONFIG_ENTRIES.size());
        assertThat(pkg.includeState()).isFalse();
    }

    @Test
    @DisplayName("state entries appear only when the export asks for them")
    void stateIsOptional() throws IOException {
        assertThat(entriesOf(export(true)).keySet())
                .containsExactlyInAnyOrderElementsOf(concat(CONFIG_ENTRIES, STATE_ENTRIES));

        ConfigExportService.ConfigPackage pkg = export(true);
        assertThat(pkg.includeState()).isTrue();
        assertThat(packageCrypto.decrypt(pkg.bytes(), PASSPHRASE).includeState()).isTrue();
    }

    @Test
    @DisplayName("history, audit and run tables never enter the package")
    void historyStaysBehind() throws IOException {
        ChangeLog log = new ChangeLog();
        log.setProjectId(7L);
        log.setObjectType(ObjectType.TABLE);
        log.setObjectName("orders");
        log.setChangeType(ChangeType.INSERT);
        log.setOccurredAt(Instant.now());
        changeLogs.save(log);

        Map<String, byte[]> entries = entriesOf(export(true));

        assertThat(entries.keySet()).noneMatch(name -> name.contains("change-log")
                || name.contains("audit") || name.contains("run") || name.contains("metric"));
    }

    @Test
    @DisplayName("stored secrets leave as ciphertext and no plaintext field name is visible")
    void secretsLeaveSealed() throws IOException {
        DatabaseConfig db = database("prod-orders", "enc:q2luZGVmaW5lZA==");
        db.setMinerPassword("enc:bWluZXI=");
        databaseConfigs.save(db);
        AiProvider ai = new AiProvider();
        ai.setName("openai");
        ai.setApiKey("enc:YWlzZWNyZXQ=");
        aiProviders.save(ai);

        ConfigExportService.ConfigPackage pkg = export(false);
        Map<String, byte[]> entries = entriesOf(pkg);

        String databasesJson = new String(entries.get("config/database-configs.json"), StandardCharsets.UTF_8);
        assertThat(databasesJson).contains("enc:q2luZGVmaW5lZA==")
                .doesNotContain("plain-password");
        assertThat(new String(entries.get("config/ai-providers.json"), StandardCharsets.UTF_8))
                .contains("enc:YWlzZWNyZXQ=");

        // The sealed outer bytes carry none of the entry names or field names.
        String sealed = new String(pkg.bytes(), StandardCharsets.ISO_8859_1);
        assertThat(sealed).doesNotContain("databaseName", "config/database-configs.json", "manifest");
    }

    @Test
    @DisplayName("meta names the schema version and the key the secrets were written with")
    void metaDescribesTheSource() throws IOException {
        databaseConfigs.save(database("orders", "enc:a2V5"));
        users.save(user("ops", "hash-ops", UserRole.VIEWER));

        JsonNode meta = json(entriesOf(export(false)), "meta.json");

        assertThat(meta.get("schemaVersion").asInt()).isEqualTo(ConfigPackageFormat.SCHEMA_VERSION);
        assertThat(meta.get("includeState").asBoolean()).isFalse();
        assertThat(meta.get("exportedAt").isTextual()).isTrue();
        assertThat(meta.get("sourceCrypto").get("password").asText())
                .isEqualTo(syncProperties.getCryptoPassword());
        assertThat(meta.get("sourceCrypto").get("salt").asText())
                .isEqualTo(syncProperties.getCryptoSalt());
        assertThat(meta.get("counts").get("databases").asInt()).isEqualTo(1);
        assertThat(meta.get("counts").get("users").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the manifest hashes every other entry and not itself")
    void manifestCoversEveryEntry() throws IOException {
        databaseConfigs.save(database("orders", "enc:a2V5"));

        Map<String, byte[]> entries = entriesOf(export(false));
        JsonNode manifest = json(entries, "manifest.json");

        assertThat(manifest.get("entries")).hasSize(entries.size() - 1);
        Set<String> covered = new TreeSet<>();
        for (JsonNode item : manifest.get("entries")) {
            String name = item.get("name").asText();
            covered.add(name);
            assertThat(item.get("size").asLong()).isEqualTo(entries.get(name).length);
            assertThat(item.get("sha256").asText())
                    .isEqualTo(ConfigPackageWriter.sha256Hex(entries.get(name)));
        }
        assertThat(covered).doesNotContain("manifest.json");
        assertThat(covered).hasSize(CONFIG_ENTRIES.size() - 1);
    }

    @Test
    @DisplayName("the counts describe what is inside, one per collection")
    void countsMatchTheRows() {
        DatabaseConfig source = databaseConfigs.save(database("src", "enc:c291cmNl"));
        DatabaseConfig target = databaseConfigs.save(database("dst", "enc:dGFyZ2V0"));
        RedisTarget redis = new RedisTarget();
        redis.setName("cache");
        redis.setHost("localhost");
        redisTargets.save(redis);
        Project project = new Project();
        project.setName("orders");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig("{}");
        projects.save(project);
        EtlTask task = new EtlTask();
        task.setName("etl");
        task.setSourceDbId(source.getId());
        task.setTargetDbId(target.getId());
        task.setSourceSql("select 1");
        task.setTargetTable("t");
        etlTasks.save(task);
        MigrationTask migration = new MigrationTask();
        migration.setName("es");
        migration.setType(com.qqmu.jync.model.MigrationType.ES_TO_EASYSEARCH);
        migration.setSourceConnectionId(1L);
        migration.setTargetConnectionId(2L);
        migration.setOptionsJson("{}");
        migrationTasks.save(migration);
        AiProvider ai = new AiProvider();
        ai.setName("ai");
        aiProviders.save(ai);
        users.save(user("ops", "hash", UserRole.VIEWER));

        ConfigExportService.ConfigPackage pkg = export(false);

        assertThat(pkg.databases()).isEqualTo(2);
        assertThat(pkg.redisTargets()).isEqualTo(1);
        assertThat(pkg.projects()).isEqualTo(1);
        assertThat(pkg.etlTasks()).isEqualTo(1);
        assertThat(pkg.etlBatches()).isZero();
        assertThat(pkg.migrationTasks()).isEqualTo(1);
        assertThat(pkg.migrationConnections()).isZero();
        assertThat(pkg.aiProviders()).isEqualTo(1);
        assertThat(pkg.users()).isEqualTo(1);
        assertThat(pkg.systemSettings()).isZero();
    }

    @Test
    @DisplayName("a package over the size ceiling is refused instead of written")
    void oversizedPackagesAreRefused() {
        assertThatThrownBy(() -> ConfigExportService.requireWithinPackageLimit(
                ConfigPackageFormat.MAX_PACKAGE_BYTES + 1))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.export.tooLarge");
        ConfigExportService.requireWithinPackageLimit(ConfigPackageFormat.MAX_PACKAGE_BYTES);
    }

    @Test
    @DisplayName("a package that would unpack past the import ceiling is refused at export time")
    void packagesTooLargeToImportAreRefused() {
        // The importer stops at the uncompressed cap. A compressed package can slip under the
        // sealed-file ceiling and still be permanently unimportable, so the export checks the
        // entries before sealing them.
        assertThatThrownBy(() -> ConfigExportService.requireWithinUncompressedLimit(
                ConfigPackageFormat.MAX_UNCOMPRESSED_BYTES + 1))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.export.tooLarge");
        ConfigExportService.requireWithinUncompressedLimit(
                ConfigPackageFormat.MAX_UNCOMPRESSED_BYTES);
    }

    private static DatabaseConfig database(String name, String password) {
        DatabaseConfig db = new DatabaseConfig();
        db.setName(name);
        db.setType(DatabaseType.MYSQL);
        db.setHost("localhost");
        db.setPort(3306);
        db.setDatabaseName(name);
        db.setUsername("app");
        db.setPassword(password);
        return db;
    }

    private static AppUser user(String name, String hash, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(name);
        user.setPasswordHash(hash);
        user.setRole(role);
        return user;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
