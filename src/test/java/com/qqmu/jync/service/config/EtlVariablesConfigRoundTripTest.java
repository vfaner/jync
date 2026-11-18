package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlCheckpointRepository;
import com.qqmu.jync.repository.EtlTaskRepository;

/**
 * The variables box travels with the task. A package that dropped it would restore a task whose
 * SQL still says {@code ${tenant}} but no longer defines it — the task would then fail every run
 * with "unknown variable", which is a worse outcome than losing the SQL itself.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:etlvarconfig;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/etlvarconfig-logs/jync.log"
})
class EtlVariablesConfigRoundTripTest {

    private static final char[] PASSPHRASE = "variables passphrase".toCharArray();

    @Autowired
    private ConfigExportService exportService;
    @Autowired
    private ConfigImportService importService;

    // The import's re-arm step starts schedulers; the export/import content is what this covers.
    @MockitoBean
    private ConfigImportRuntime runtime;

    @Autowired
    private DatabaseConfigRepository databaseConfigs;
    @Autowired
    private EtlBatchRepository etlBatches;
    @Autowired
    private EtlTaskRepository etlTasks;
    @Autowired
    private EtlCheckpointRepository etlCheckpoints;
    @Autowired
    private AppUserRepository users;

    @BeforeEach
    void clean() {
        etlCheckpoints.deleteAll();
        etlTasks.deleteAll();
        etlBatches.deleteAll();
        databaseConfigs.deleteAll();
        users.deleteAll();
    }

    @Test
    void theVariablesSurviveAnExportAndImport() {
        users.save(user());
        DatabaseConfig source = databaseConfigs.save(database("src"));
        DatabaseConfig target = databaseConfigs.save(database("dst"));
        EtlTask task = new EtlTask();
        task.setName("orders");
        task.setSourceDbId(source.getId());
        task.setTargetDbId(target.getId());
        task.setSourceSql("SELECT id FROM src WHERE tenant = '${tenant}' AND dt = '${date}'");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setVariables("tenant='acme'");
        etlTasks.save(task);

        byte[] pkg = exportService.build(PASSPHRASE, false).bytes();
        clean();

        importService.importPackage(pkg, PASSPHRASE, true);

        assertThat(etlTasks.findAll()).singleElement().satisfies(restored -> {
            assertThat(restored.getVariables()).isEqualTo("tenant='acme'");
            assertThat(restored.getSourceSql())
                    .isEqualTo("SELECT id FROM src WHERE tenant = '${tenant}' AND dt = '${date}'");
        });
    }

    // --- helpers ------------------------------------------------------------------------

    private static DatabaseConfig database(String name) {
        DatabaseConfig db = new DatabaseConfig();
        db.setName(name);
        db.setType(DatabaseType.MYSQL);
        db.setHost("localhost");
        db.setPort(3306);
        db.setDatabaseName(name);
        db.setUsername("app");
        return db;
    }

    private static AppUser user() {
        AppUser user = new AppUser();
        user.setUsername("admin");
        user.setPasswordHash("hash-admin");
        user.setRole(UserRole.ADMIN);
        return user;
    }
}
