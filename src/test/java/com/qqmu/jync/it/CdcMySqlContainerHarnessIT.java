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

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Shared real-MySQL harness for the binlog CDC integration tests (task book D2 / 验收 B).
 *
 * <p>One {@code mysql:8.0} container shared by default across the IT family: started once
 * (lazily, from the first {@code @BeforeEach}) and deliberately NOT managed by
 * {@code @Container} (the Testcontainers extension would stop it after every class); Ryuk
 * reclaims it when the JVM exits. The command flags turn the stock image into a replication
 * source: ROW-format binlog with the FULL row image (the CDC mapper needs every column of
 * the before-image for PK moves), GTID mode on (positions resume through the executed set),
 * a fixed UTC clock so binlog timestamps never wobble with the host zone, and
 * {@code --sql-mode=ALLOW_INVALID_DATES} so the Task 15 type matrix can insert zero-date
 * fixtures — the sql-mode flag replaces the stock mode entirely (no NO_ZERO_DATE, no strict
 * mode); every other test writes strictly valid values and is unaffected. Classes whose
 * tests mutate server state destructively (PURGE BINARY LOGS, docker pause) override
 * {@link #createContainer()} to run against their OWN instance instead of poisoning the
 * shared one.
 *
 * <p>Two schemas on that one instance — {@code src} (source) and {@code tgt} (target) —
 * created at startup; each side's JDBC URL simply points at one schema, so the whole
 * source→target pipeline runs against real binlog traffic without a second container.
 * Table structures are hand-created by each test (no StructureSyncService): these ITs
 * exercise the data plane only.
 *
 * <p>Both endpoints use the container's {@code root} account: a binlog consumer must
 * present REPLICATION SLAVE / REPLICATION CLIENT, which root has by definition. The
 * password travels through {@link CryptoUtil#encrypt} exactly like a UI-saved connection.
 *
 * <p>Spring side: the full application context on the default in-memory H2 metadata store
 * (test convention per {@code SecurityConfigTest} — never the file store, so the suite
 * leaves no {@code ./data} behind; {@code ddl-auto:update} creates {@code cdc_position}),
 * MOCK web environment (no port 8080 binding), and fast lease/supervise/reconcile
 * intervals because the takeover-oriented tests cannot wait out production 30s windows.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "sync.cdc.lock-ttl-ms=3000",
        "sync.cdc.supervise-interval-ms=1000",
        "sync.lock-ttl-ms=3000",
        "sync.reconcile-interval-ms=1000",
        "sync.shutdown-wait-ms=5000",
        // Metadata store: the app's default H2, in-memory per the existing @SpringBootTest
        // convention. DB_CLOSE_ON_EXIT=FALSE keeps H2 from self-closing inside the VM
        // shutdown hook before the app's own hook has stopped the consumers and the lock
        // watchdog — those last writes would otherwise log spurious "Database is already
        // closed" errors after every run. AI and the GitHub update check stay off —
        // nothing reaches the network.
        "spring.datasource.url=jdbc:h2:mem:cdcit;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
public abstract class CdcMySqlContainerHarnessIT {

    protected static final String SRC_SCHEMA = "src";
    protected static final String TGT_SCHEMA = "tgt";

    /**
     * The family-shared MySQL 8 replication source, created lazily on first use — a
     * subclass overriding {@link #createContainer()} must not even construct the shared
     * instance. See {@link #configuredMySql()} for the flags.
     */
    private static MySQLContainer<?> sharedMySql;

    /**
     * Builds a fresh {@code mysql:8.0} replication source carrying the family's full
     * command line. {@code withCommand} REPLACES the image CMD, so all eight flags live in
     * this one call: the seven Task 14 flags verbatim (ROW binlog with FULL row image, GTID
     * mode on, fixed UTC clock) plus {@code --sql-mode=ALLOW_INVALID_DATES}, which replaces
     * the stock sql_mode (dropping NO_ZERO_DATE and strict mode) so the zero-date fixture
     * can insert {@code '0000-00-00'}.
     *
     * <p>{@code --max-allowed-packet=64M} is required by the v1.7.8 5MB LOB test: the stock
     * 1MB ceiling truncates both {@code REPEAT()} on the server and the binlog event on the
     * wire, which would fail before the sync code is reached.
     */
    protected static MySQLContainer<?> configuredMySql() {
        return new MySQLContainer<>("mysql:8.0")
                .withCommand("--server-id=1",
                        "--log-bin=mysql-bin",
                        "--binlog-format=ROW",
                        "--binlog-row-image=FULL",
                        "--gtid-mode=ON",
                        "--enforce-gtid-consistency=ON",
                        "--default-time-zone=+00:00",
                        "--max-allowed-packet=64M",
                        "--sql-mode=ALLOW_INVALID_DATES");
    }

    /**
     * The container this test class runs against. Default: the single instance shared by
     * the whole family. Classes whose tests mutate server state destructively
     * ({@code PURGE BINARY LOGS}, {@code docker pause}) MUST override this to return their
     * OWN instance — never reuse or stop the shared one — so that default subclasses keep
     * byte-identical container behavior: same one shared container, same flags, started
     * once, idempotent schema creation.
     */
    protected MySQLContainer<?> createContainer() {
        synchronized (CdcMySqlContainerHarnessIT.class) {
            if (sharedMySql == null) {
                sharedMySql = configuredMySql();
            }
            return sharedMySql;
        }
    }

    @Autowired
    protected ProjectRepository projectRepository;

    @Autowired
    protected DatabaseConfigRepository databaseConfigRepository;

    @Autowired
    protected SyncContextFactory contextFactory;

    @Autowired
    protected SyncTaskStore taskStore;

    @Autowired
    protected CryptoUtil cryptoUtil;

    /**
     * Instance method, not {@code @BeforeAll}: {@link #createContainer()} is overridable
     * per subclass, and an override cannot be called from a static context. Behavior for
     * non-overriding classes is unchanged — the shared container starts once (guarded by
     * {@code isRunning}) and {@code CREATE DATABASE IF NOT EXISTS} is idempotent per test.
     */
    @BeforeEach
    void startMySql() throws SQLException {
        MySQLContainer<?> mysql = createContainer();
        if (!mysql.isRunning()) {
            mysql.start();
        }
        try (Connection conn = DriverManager.getConnection(
                jdbcUrl(mysql.getDatabaseName()), "root", mysql.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SRC_SCHEMA);
            st.execute("CREATE DATABASE IF NOT EXISTS " + TGT_SCHEMA);
        }
    }

    /** Direct root connection to one schema of this class's container (plain JDBC, no pool). */
    protected String jdbcUrl(String schema) {
        MySQLContainer<?> mysql = createContainer();
        return "jdbc:mysql://" + mysql.getHost() + ":"
                + mysql.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    protected Connection sourceJdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(SRC_SCHEMA), "root", createContainer().getPassword());
    }

    protected Connection targetJdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(TGT_SCHEMA), "root", createContainer().getPassword());
    }

    /**
     * A fresh saved {@link DatabaseConfig} pointing at the container's {@code src} schema,
     * password encrypted like the UI saves it. One row per call on purpose: every project
     * gets its own config id, so {@code DataSourceManager}'s per-id pool cache never mixes
     * two projects' connections.
     */
    protected DatabaseConfig sourceConfig() {
        return savedConfig("cdc-it-src", SRC_SCHEMA, ConnectionRole.SOURCE);
    }

    /** The {@code tgt}-schema twin of {@link #sourceConfig()}. */
    protected DatabaseConfig targetConfig() {
        return savedConfig("cdc-it-tgt", TGT_SCHEMA, ConnectionRole.TARGET);
    }

    private DatabaseConfig savedConfig(String name, String schema, ConnectionRole role) {
        MySQLContainer<?> mysql = createContainer();
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        config.setRole(role);
        config.setHost(mysql.getHost());
        config.setPort(mysql.getMappedPort(MySQLContainer.MYSQL_PORT));
        config.setDatabaseName(schema);
        config.setSchemaName(schema);
        config.setUsername("root");                       // binlog needs REPLICATION SLAVE/CLIENT
        config.setPassword(cryptoUtil.encrypt(mysql.getPassword()));
        return databaseConfigRepository.save(config);
    }

    /**
     * Saves an enabled BINLOG_CDC-ready project wired to fresh src/tgt configs, with the
     * given {@link SyncConfig} serialized exactly the way {@code SyncContextFactory}
     * stores and parses it.
     *
     * <p>{@code enabled=true} is load-bearing: the 1s reconcile/supervise ticks converge
     * against that flag — a disabled project's consumer would be stopped by the very next
     * tick. {@code taskStore.ensureTask} mirrors {@code ProjectService.save}: without the
     * sync_task row, {@code markCdcRunning} / {@code recordCdcFailure} (and with it the B7
     * ERROR hat) would silently no-op for repository-created projects.
     */
    protected Project newProject(String name, SyncConfig config) {
        return newProject(name, config, true);
    }

    /**
     * The {@code enabled}-flag overload, for fixtures that must land state BEFORE the
     * project becomes startable by the 1s reconcile/supervise convergence — e.g. a faked
     * crash-mid-baseline position row: a consumer started before the fake write would run
     * a normal baseline, and its {@code markBaselineDone} racing the fake write could
     * leave {@code baseline_done=false} on an already-streaming consumer forever.
     * Save disabled, write the fixture, flip the flag and save, then start.
     * {@code ensureStarted} itself never checks the flag, so an explicit start works in
     * either state.
     */
    protected Project newProject(String name, SyncConfig config, boolean enabled) {
        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(sourceConfig().getId());
        project.setTargetDbId(targetConfig().getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(enabled);
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);
        return saved;
    }

    /**
     * Per-test teardown: flips the project off so the 1s reconcile/supervise convergence
     * stops relaunching its consumer for the rest of the shared Spring context's life.
     * Without this, every finished test leaves a live consumer behind that the next IT
     * class's context (same in-memory H2, same container) inherits and restarts at boot.
     */
    protected void disableProject(Project project) {
        Project fresh = reload(project);
        fresh.setEnabled(false);
        projectRepository.save(fresh);
    }

    /** Re-reads the project row — entities handed around a test go stale after restarts. */
    protected Project reload(Project project) {
        return projectRepository.findById(project.getId())
                .orElseThrow(() -> new AssertionError(
                        "Project " + project.getId() + " vanished from the repository"));
    }

    /**
     * Polls {@code condition} every 200ms until it holds; {@link AssertionError} on
     * timeout. No Awaitility — the project does not carry that dependency. The deadline
     * is {@code nanoTime}-based: wall-clock jumps (NTP, sleep) must not shorten or extend
     * a wait.
     */
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

    /** Row count of one table in the target ({@code tgt}) schema. */
    protected long countTarget(String table) {
        return countTargetTable(table);
    }

    /** Same as {@link #countTarget}; second name kept for the brief's call sites. */
    protected long countTargetTable(String table) {
        String sql = "SELECT COUNT(*) FROM " + table;
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

    /** Runs a query against the target schema; rows are label-keyed maps of raw JDBC values. */
    protected List<Map<String, Object>> queryTarget(String sql) {
        try (Connection conn = targetJdbc()) {
            return queryAll(conn, sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Target query failed: " + sql, e);
        }
    }

    /** Assertion utility: every row as a column-label → value map, in result-set order. */
    protected List<Map<String, Object>> queryAll(Connection conn, String sql) throws SQLException {
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
    }
}
