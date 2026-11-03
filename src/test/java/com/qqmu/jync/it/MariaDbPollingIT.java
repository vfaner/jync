package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.ContentAuditRunner;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Real-database matrix: MariaDB 11.4 as both source and target, polling strategy.
 *
 * <p>This is the product the bundled driver has always claimed to support but no container
 * IT exercised before v1.12.0. One instance carries two databases ({@code src} and
 * {@code tgt}); the cycle is driven directly through {@link SyncEngine#runCycle} so the
 * assertions are about SQL behaviour, not the scheduler.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mariadbit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
class MariaDbPollingIT {

    private static final String USER = "root";
    private static final String PASSWORD = "test";

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"))
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword(PASSWORD);

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private ContentAuditRunner contentAuditRunner;
    @Autowired private DataSourceManager dataSourceManager;
    @Autowired private CryptoUtil cryptoUtil;

    @BeforeAll
    static void createDatabases() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl("test"), USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS src");
            st.execute("CREATE DATABASE IF NOT EXISTS tgt");
        }
    }

    @Test
    void aPollingCycleCreatesTheTargetCopiesRowsSendsUpdatesAndAuditsCleanly()
            throws SQLException {
        String table = "MD_ORDERS";
        try (Connection src = DriverManager.getConnection(jdbcUrl("src"), USER, PASSWORD);
             Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute("CREATE TABLE " + table + " (ID BIGINT PRIMARY KEY,"
                    + " NAME VARCHAR(50) NOT NULL, AMOUNT DECIMAL(10,2),"
                    + " UPDATED_AT TIMESTAMP(6) NOT NULL,"
                    + " KEY idx_updated (UPDATED_AT))");
            st.execute("INSERT INTO " + table + " VALUES"
                    + " (1,'ann',10.50,'2026-01-01 00:00:00'),"
                    + " (2,'bob',20.00,'2026-01-01 00:00:01'),"
                    + " (3,'cid',30.25,'2026-01-01 00:00:02')");
        }
        Project project = savedProject();

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
        assertThat(first.getRowsInserted()).isEqualTo(3);
        assertThat(rowNames(table)).containsEntry("1", "ann").containsEntry("2", "bob")
                .containsEntry("3", "cid");

        SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(second.isSuccess()).as("空转错误: %s", second.getErrors()).isTrue();
        assertThat(second.getRowsInserted()).isZero();
        assertThat(second.getRowsUpdated()).isZero();

        // The explicit UPDATED_AT cursor is what lets polling see the change; identity-only
        // polling never would.
        try (Connection src = DriverManager.getConnection(jdbcUrl("src"), USER, PASSWORD);
             Statement st = src.createStatement()) {
            st.execute("UPDATE " + table + " SET NAME = 'ann-2',"
                    + " UPDATED_AT = '2026-01-02 00:00:00' WHERE ID = 1");
        }
        SyncResult third = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(third.isSuccess()).as("更新轮错误: %s", third.getErrors()).isTrue();
        assertThat(third.getRowsInserted()).isZero();
        // The window also replays row 3 from the safety margin. MariaDB's bulk-statement
        // rewrite (SUCCESS_NO_INFO) reports the statement's ROW_COUNT() as one total, which
        // the conservative accounting counts as an upper bound — 2 here for one real update.
        // The delivered value below is what proves the update arrived.
        assertThat(third.getRowsUpdated()).isGreaterThanOrEqualTo(1);
        assertThat(rowNames(table)).containsEntry("1", "ann-2");

        TableContentAudit audit = contentAuditRunner.auditProject(project.getId(), table);

        assertThat(audit.status()).as("审计明细: %s", audit.detail())
                .isEqualTo(TableContentAudit.Status.OK);
        // The audit compares only rows at or below the persisted watermark, and the safety
        // margin has just rewound it by one second — so the updated row sits outside the
        // bound. The rows it does sample (including the TIMESTAMP source against the
        // DATETIME target column) must all match.
        assertThat(audit.sampled()).isPositive();
        assertThat(audit.mismatched()).isZero();
    }

    private Project savedProject() {
        DatabaseConfig source = savedConfig("md-it-src", "src", ConnectionRole.SOURCE);
        DatabaseConfig target = savedConfig("md-it-tgt", "tgt", ConnectionRole.TARGET);
        SyncConfig config = new SyncConfig();
        // Force the timestamp cursor: the update assertion is about polling, not about an
        // identity cursor that would never revisit row 1.
        config.setCursorColumns(Map.of("MD_ORDERS", "UPDATED_AT"));
        Project project = new Project();
        project.setName("mariadb-polling-it");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(false);
        return projectRepository.save(project);
    }

    private DatabaseConfig savedConfig(String name, String database, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.MARIADB);
        config.setRole(role);
        config.setHost(MARIADB.getHost());
        config.setPort(MARIADB.getMappedPort(3306));
        config.setDatabaseName(database);
        config.setSchemaName(database);
        config.setUsername(USER);
        config.setPassword(cryptoUtil.encrypt(PASSWORD));
        return databaseConfigRepository.save(config);
    }

    private Map<String, String> rowNames(String table) throws SQLException {
        DatabaseConfig target = databaseConfigRepository.findByName("md-it-tgt")
                .orElseThrow();
        try (Connection conn = dataSourceManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ID, NAME FROM " + table + " ORDER BY ID")) {
            Map<String, String> rows = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                rows.put(String.valueOf(rs.getLong("ID")), rs.getString("NAME"));
            }
            return rows;
        }
    }

    private static String jdbcUrl(String database) {
        return "jdbc:mariadb://" + MARIADB.getHost() + ":"
                + MARIADB.getMappedPort(3306) + "/" + database;
    }
}
