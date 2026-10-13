package com.qqmu.jync.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Shared real PostgreSQL 16 source for the logical-replication CDC docker matrix.
 *
 * <p>Mirrors {@link CdcMySqlContainerHarnessIT}: one container, lazily started from the
 * first {@code @BeforeEach}, deliberately not {@code @Container}-managed (Ryuk reaps it at
 * JVM exit). The single command flag turns on logical decoding; the stock image ships the
 * {@code test_decoding} plugin the consumer uses, and its default user is a superuser so
 * it may create replication slots. Targets are swappable: the openGauss IT overrides
 * {@link #targetJdbc()} / {@link #targetConfig()}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "sync.cdc.lock-ttl-ms=3000",
        "sync.cdc.supervise-interval-ms=1000",
        "sync.lock-ttl-ms=3000",
        "sync.reconcile-interval-ms=1000",
        "sync.shutdown-wait-ms=5000",
        "spring.datasource.url=jdbc:h2:mem:cdcitpg;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
abstract class PgCdcHarnessIT {

    protected static final String SCHEMA = "public";
    protected static final String DB_USER = "test";
    protected static final String DB_PASSWORD = "test";
    protected static final String DB_NAME = "test";
    protected static final int PG_PORT = 5432;

    private static GenericContainer<?> sharedPg;

    protected static GenericContainer<?> configuredPg() {
        // wal_level needs a server restart to take effect, hence a command flag rather
        // than ALTER SYSTEM. The testcontainers postgres module isn't a dependency, so
        // this is a plain GenericContainer with the stock image's POSTGRES_* env vars.
        return new GenericContainer<>(DockerImageName.parse("postgres:16"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .withEnv("POSTGRES_USER", DB_USER)
                .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
                .withEnv("POSTGRES_DB", DB_NAME)
                .withExposedPorts(PG_PORT)
                .waitingFor(Wait.forListeningPort()
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    protected GenericContainer<?> createContainer() {
        synchronized (PgCdcHarnessIT.class) {
            if (sharedPg == null) {
                sharedPg = configuredPg();
            }
            return sharedPg;
        }
    }

    @Autowired protected ProjectRepository projectRepository;
    @Autowired protected DatabaseConfigRepository databaseConfigRepository;
    @Autowired protected SyncContextFactory contextFactory;
    @Autowired protected SyncTaskStore taskStore;
    @Autowired protected CryptoUtil cryptoUtil;

    @BeforeEach
    void startPg() {
        createContainer().start();
    }

    protected String jdbcUrl() {
        GenericContainer<?> pg = createContainer();
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(PG_PORT)
                + "/" + DB_NAME;
    }

    protected Connection sourceJdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DB_USER, DB_PASSWORD);
    }

    protected Connection targetJdbc() throws SQLException {
        // Default: the same PostgreSQL instance is also the target. Cross-engine ITs
        // override this (and targetConfig) with the 信创 target.
        return sourceJdbc();
    }

    protected DatabaseConfig sourceConfig() {
        return savedConfig("cdc-pg-src", ConnectionRole.SOURCE, DatabaseType.POSTGRESQL,
                DB_NAME);
    }

    protected DatabaseConfig targetConfig() {
        return savedConfig("cdc-pg-tgt", ConnectionRole.TARGET, DatabaseType.POSTGRESQL,
                DB_NAME);
    }

    private DatabaseConfig savedConfig(String name, ConnectionRole role, DatabaseType type,
                                       String database) {
        GenericContainer<?> pg = createContainer();
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(type);
        config.setRole(role);
        config.setHost(pg.getHost());
        config.setPort(pg.getMappedPort(PG_PORT));
        config.setDatabaseName(database);
        config.setSchemaName(SCHEMA);
        config.setUsername(DB_USER);
        config.setPassword(cryptoUtil.encrypt(DB_PASSWORD));
        return databaseConfigRepository.save(config);
    }

    protected Project newProject(String name, SyncConfig config) {
        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(sourceConfig().getId());
        project.setTargetDbId(targetConfig().getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(true);
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);
        return saved;
    }

    protected void disableProject(Project project) {
        Project fresh = reload(project);
        fresh.setEnabled(false);
        projectRepository.save(fresh);
    }

    protected Project reload(Project project) {
        return projectRepository.findById(project.getId())
                .orElseThrow(() -> new AssertionError(
                        "Project " + project.getId() + " vanished from the repository"));
    }

    protected void waitFor(String what, long timeoutMs, BooleanSupplier condition) {
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainingMs <= 0) {
                throw new AssertionError(
                        "Timed out after " + timeoutMs + " ms waiting for: " + what);
            }
            try {
                Thread.sleep(Math.min(200L, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
    }

    protected long countTarget(String table) {
        String sql = "SELECT COUNT(*) FROM " + SCHEMA + "." + table;
        try (Connection conn = targetJdbc();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("COUNT(*) returned no row: " + sql);
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Target count failed: " + sql, e);
        }
    }

    protected List<Map<String, Object>> queryTarget(String sql) {
        try (Connection conn = targetJdbc()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Target query failed: " + sql, e);
        }
    }

    protected String queryTaskStatus(long projectId) {
        return taskStore.find(projectId)
                .map(t -> t.getStatus() == null ? null : t.getStatus().name())
                .orElse(null);
    }
}
