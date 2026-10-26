package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.cdc.LogCapture;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.metadata.OracleMetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * The initial-load INSERT shortcut against a real Oracle: on an empty target the load must
 * choose INSERT exactly once per table (not once per chunk, which would fall back to MERGE
 * from chunk two onward), and on a target that already has rows it must keep the idempotent
 * MERGE — an INSERT there would raise a unique violation and fail the load.
 *
 * <p>Uses the same 23ai Free image as the rest of the Oracle ITs and disables itself without
 * Docker. Run with {@code mvn test -Dtest=OracleInitialLoadInsertFirstIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleInitialLoadInsertFirstIT {

    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"));

    private static final String SRC = "INS_SRC";
    private static final int ROWS = 2_500;
    private static final int CHUNK = 500;
    private static final String DDL = " (ID NUMBER(18) PRIMARY KEY, NAME VARCHAR2(50))";

    private static String schema;

    @BeforeAll
    static void prepare() throws SQLException {
        schema = ORACLE.getUsername().toUpperCase(Locale.ROOT);
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + SRC + DDL);
            st.execute("INSERT INTO " + SRC + " SELECT LEVEL, 'row-' || LEVEL FROM dual"
                    + " CONNECT BY LEVEL <= " + ROWS);
            st.execute("COMMIT");
        }
    }

    @Test
    void anEmptyTargetUsesInsertOncePerLoadAndAResumedTargetKeepsMerge() throws SQLException {
        // Empty target, sequential: five chunks of 500, one decision for all of them.
        recreate("INS_TGT_SEQ");
        ListAppender<ILoggingEvent> seqLogs = LogCapture.attach(DataSyncService.class);
        DataSyncService.TableSyncResult seq;
        try {
            seq = run("INS_TGT_SEQ", 1);
        } finally {
            LogCapture.detach(DataSyncService.class, seqLogs);
        }
        assertThat(seq.isSuccess()).as(seq.getError()).isTrue();
        assertThat(count("INS_TGT_SEQ")).isEqualTo(ROWS);
        assertThat(insertAnnouncements(seqLogs)).isEqualTo(1);

        // Empty target, two workers: the ranges are disjoint, so both may INSERT safely.
        recreate("INS_TGT_PAR");
        ListAppender<ILoggingEvent> parLogs = LogCapture.attach(DataSyncService.class);
        DataSyncService.TableSyncResult par;
        try {
            par = run("INS_TGT_PAR", 2);
        } finally {
            LogCapture.detach(DataSyncService.class, parLogs);
        }
        assertThat(par.isSuccess()).as(par.getError()).isTrue();
        assertThat(count("INS_TGT_PAR")).isEqualTo(ROWS);
        assertThat(insertAnnouncements(parLogs)).isEqualTo(1);

        // A target that already holds every key must NOT take the INSERT path: the load has
        // to update the stale names through MERGE and stay successful.
        recreate("INS_TGT_FULL");
        exec("INSERT INTO INS_TGT_FULL SELECT LEVEL, 'stale' FROM dual"
                + " CONNECT BY LEVEL <= " + ROWS);
        exec("COMMIT");
        ListAppender<ILoggingEvent> fullLogs = LogCapture.attach(DataSyncService.class);
        DataSyncService.TableSyncResult full;
        try {
            full = run("INS_TGT_FULL", 1);
        } finally {
            LogCapture.detach(DataSyncService.class, fullLogs);
        }
        assertThat(full.isSuccess()).as(full.getError()).isTrue();
        assertThat(count("INS_TGT_FULL")).isEqualTo(ROWS);
        assertThat(queryString("SELECT NAME FROM INS_TGT_FULL WHERE ID = 1")).isEqualTo("row-1");
        assertThat(queryLong("SELECT COUNT(*) FROM INS_TGT_FULL WHERE NAME = 'stale'"))
                .isZero();
        assertThat(insertAnnouncements(fullLogs)).isZero();
    }

    private static long insertAnnouncements(ListAppender<ILoggingEvent> logs) {
        return LogCapture.messagesAt(Level.INFO, logs).stream()
                .filter(message -> message.contains("writes INSERT"))
                .count();
    }

    private DataSyncService.TableSyncResult run(String targetTable, int parallelism)
            throws SQLException {
        ChunkCheckpointRepository checkpoints = mock(ChunkCheckpointRepository.class);
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(CHUNK);
        properties.setFullLoadParallelism(parallelism);

        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(new LinkedHashMap<>(Map.of(SRC, targetTable)));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(21L);
        sourceCfg.setType(DatabaseType.ORACLE);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(22L);
        targetCfg.setType(DatabaseType.ORACLE);

        Project project = new Project();
        project.setId(3L);
        project.setName("insert-first-it");

        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.ORACLE)
                .targetType(DatabaseType.ORACLE)
                .sourceSchema(schema)
                .targetSchema(schema)
                .sourceDialect(new OracleDialect())
                .targetDialect(new OracleDialect())
                .batchSize(100)
                .fullLoadBatchSize(100)
                .fullLoadFetchSize(100)
                .fullLoadParallelism(parallelism)
                .connectionProvider(cfg -> open())
                .build();

        TableMeta meta;
        try (Connection conn = open()) {
            meta = new OracleMetadataReader().readTable(conn, schema, SRC);
        }
        CursorStrategy strategy = CursorStrategy.identity("ID", Types.NUMERIC,
                "numeric primary key");

        DataSyncService service = new DataSyncService(properties, checkpoints);
        try (Connection sourceConn = open(); Connection targetConn = open()) {
            return service.syncTable(sourceConn, targetConn, meta, strategy,
                    new SyncProgress(), ctx);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }

    private static void recreate(String table) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            try {
                st.execute("DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE");
            } catch (SQLException ignored) {
                // First run.
            }
            st.execute("CREATE TABLE " + table + DDL);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static long count(String table) throws SQLException {
        return queryLong("SELECT COUNT(*) FROM " + table);
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
