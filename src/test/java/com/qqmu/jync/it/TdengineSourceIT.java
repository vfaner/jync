package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Real-database matrix: TDengine 3.3.6 as a polling source into PostgreSQL 16.
 *
 * <p>The source carries a column-level primary key, so the table's real key is
 * {@code [ts, id]} (the timestamp is always unique) and the target must recreate the composite
 * key. The VARCHAR column proves bytes are read as text, and the idle cycle proves the
 * timestamp cursor advances without shifting the wall clock.
 *
 * <p>TDengine 3.3.6 rejects a table-level {@code PRIMARY KEY (...)}; only the column-level
 * form creates. The fixture uses that form on purpose.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tdengineit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false",
        // Three pages for the row count below; the default 100000 would hide a paging bug.
        "sync.chunk-size=2"
})
class TdengineSourceIT {

    private static final String SOURCE_DB = "it_src";
    private static final String TABLE = "events";
    private static final int ROWS = 5;
    private static final String USER = "root";
    private static final String PASSWORD = "taosdata";

    @Container
    private static final GenericContainer<?> TDENGINE =
            new GenericContainer<>(DockerImageName.parse("tdengine/tdengine:3.3.6.0"))
                    .withExposedPorts(6041)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(3)));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("tgt").withUsername("test").withPassword("test");

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private MetadataReaderFactory readerFactory;
    @Autowired private CryptoUtil cryptoUtil;

    @Test
    void aTdengineTableLoadsWithItsCompositeKeyIntoPostgres() throws Exception {
        createSource();
        Project project = savedProject("tdengine-source-it", TABLE, "ts");

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
        assertThat(first.getRowsInserted()).isEqualTo(ROWS);
        assertThat(targetRowCount()).isEqualTo(ROWS);
        assertThat(targetPrimaryKey()).containsExactly("ts", "id");
        assertThat(targetTypes()).containsEntry("name", "character varying");
        assertThat(targetValues()).containsExactly("evt-1", "evt-2", "evt-3", "evt-4", "evt-5");

        SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(second.isSuccess()).as("空转错误: %s", second.getErrors()).isTrue();
        assertThat(second.getRowsInserted()).isZero();
        assertThat(second.getRowsUpdated()).isZero();
        assertThat(targetRowCount()).isEqualTo(ROWS);

        // 超级表进入表清单,子表不进入。
        MetadataReader reader = readerFactory.forType(DatabaseType.TDENGINE);
        try (Connection conn = DriverManager.getConnection(
                "jdbc:TAOS-WS://" + TDENGINE.getHost() + ":"
                        + TDENGINE.getMappedPort(6041) + "/" + SOURCE_DB, USER, PASSWORD)) {
            assertThat(reader.listTableNames(conn, SOURCE_DB))
                    .contains(TABLE, "meters").doesNotContain("m1");
        }

        numericCursorAdvancesByMax();
        stableWithTagsLoadsAsOneTable();
        deletionSyncMatchesTextKeys();
    }

    /**
     * 删除检测逐键比对两侧。TDengine 驱动把 VARCHAR 主键按 {@code byte[]} 答出来(实测
     * 3.3.6),裸读会把键渲染成十六进制,目标表存的是文本——每一行都被判成「源库已删除」,
     * 一次周期就把整张目标表清空。只有真正删掉的那一行才该消失。
     */
    private void deletionSyncMatchesTextKeys() throws Exception {
        try (Connection conn = sourceConnection(); Statement st = conn.createStatement()) {
            // 第一列必须是 TIMESTAMP;列级主键建在 VARCHAR 上,键里同时含时间戳与文本。
            st.execute("CREATE TABLE " + SOURCE_DB + ".vtab"
                    + " (ts TIMESTAMP, sn VARCHAR(32) PRIMARY KEY, val DOUBLE)");
            st.execute("INSERT INTO " + SOURCE_DB + ".vtab VALUES"
                    + " ('2026-01-01 00:00:01.000', 'sn-1', 1.0)"
                    + " ('2026-01-01 00:00:02.000', 'sn-2', 2.0)"
                    + " ('2026-01-01 00:00:03.000', 'sn-3', 3.0)");
        }
        Project project = savedProject("tdengine-delete-it", "vtab", "ts", true);

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
        assertThat(first.isSuccess()).as("删除检测首轮错误: %s", first.getErrors()).isTrue();
        assertThat(targetVtabNames()).containsExactly("sn-1", "sn-2", "sn-3");

        try (Connection conn = sourceConnection(); Statement st = conn.createStatement()) {
            // TDengine 只接受带时间窗的 DELETE(0x2655),不能只按主键删。
            st.execute("DELETE FROM " + SOURCE_DB + ".vtab"
                    + " WHERE ts >= '2026-01-01 00:00:02.000'"
                    + " AND ts <= '2026-01-01 00:00:02.000'");
        }
        SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
        assertThat(second.isSuccess()).as("删除轮错误: %s", second.getErrors()).isTrue();
        assertThat(targetVtabNames()).as("只有源库真正删除的那一行")
                .containsExactly("sn-1", "sn-3");
    }

    private java.util.List<String> targetVtabNames() throws Exception {
        java.util.List<String> values = new java.util.ArrayList<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT sn FROM public.vtab ORDER BY ts")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /**
     * A super table reads like one table whose rows carry their child's tag values. The
     * composite key {@code (ts, sn)} is recreated on the target, the tag is read as an
     * ordinary column, and the second cycle must be idle.
     */
    private void stableWithTagsLoadsAsOneTable() throws Exception {
        try (Connection conn = sourceConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + SOURCE_DB + ".m2 USING " + SOURCE_DB
                    + ".meters TAGS ('sn-2')");
            st.execute("INSERT INTO " + SOURCE_DB + ".m1 VALUES"
                    + " ('2026-01-01 00:00:01.000', 1.5)"
                    + " ('2026-01-01 00:00:03.000', 2.5)");
            st.execute("INSERT INTO " + SOURCE_DB + ".m2 VALUES"
                    + " ('2026-01-01 00:00:02.000', 9.5)");
        }
        Project project = savedProject("tdengine-stable-it", "meters", "ts");

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
        assertThat(first.isSuccess()).as("超级表首轮错误: %s", first.getErrors()).isTrue();
        assertThat(first.getRowsInserted()).isEqualTo(3);
        assertThat(targetMeterNames()).containsExactly("sn-1", "sn-2", "sn-1");
        assertThat(targetPrimaryKey("meters")).containsExactly("ts", "sn");
        // TDengine stores an instant and renders it in the reading session's zone; the target
        // must carry the same wall clock the source driver showed Jync, not the UTC edge.
        assertThat(targetMeterTimestamps()).containsExactlyElementsOf(sourceMeterTimestamps());

        SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
        assertThat(second.isSuccess()).as("超级表空转错误: %s", second.getErrors()).isTrue();
        assertThat(second.totalRows()).isZero();
    }

    /**
     * A numeric cursor takes {@code MAX}, not {@code LAST}: with rows (t1,1), (t2,3),
     * (t3,2) the two disagree, and a late row whose id is the largest but whose timestamp
     * is the smallest is only reachable through {@code MAX(id)}.
     */
    private void numericCursorAdvancesByMax() throws Exception {
        try (Connection conn = sourceConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + SOURCE_DB + ".unsorted"
                    + " (ts TIMESTAMP, id INT PRIMARY KEY, name VARCHAR(32))");
            st.execute("INSERT INTO " + SOURCE_DB + ".unsorted VALUES"
                    + " ('2026-01-01 00:00:01.000', 1, 'u-1'),"
                    + " ('2026-01-01 00:00:02.000', 2, 'u-2')");
        }
        Project project = savedProject("tdengine-numeric-it", "unsorted", "id");

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
        assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
        assertThat(first.getRowsInserted()).isEqualTo(2);

        // An id larger than every synced one, on a timestamp older than every row: LAST(id)
        // would answer 2 and the window (2, 2] would never see it.
        try (Connection conn = sourceConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO " + SOURCE_DB + ".unsorted"
                    + " VALUES ('2026-01-01 00:00:00.000', 3, 'u-3')");
        }

        SyncResult third = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
        assertThat(third.isSuccess()).as("迟推行错误: %s", third.getErrors()).isTrue();
        // The PostgreSQL upsert does not report insert vs update per row, so a windowed row
        // arrives unclassified; what matters is that the row was read and written at all.
        assertThat(third.totalRows()).isEqualTo(1);
        assertThat(targetNames("unsorted")).containsExactly("u-1", "u-2", "u-3");
    }

    private void createSource() throws Exception {
        Class.forName(DatabaseType.TDENGINE.getDriverClassName());
        try (Connection conn = DriverManager.getConnection(
                "jdbc:TAOS-WS://" + TDENGINE.getHost() + ":"
                        + TDENGINE.getMappedPort(6041) + "/", USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SOURCE_DB);
            // Column-level PRIMARY KEY: TDengine 3.3.6 rejects a table-level key.
            st.execute("CREATE TABLE " + SOURCE_DB + "." + TABLE
                    + " (ts TIMESTAMP, id INT PRIMARY KEY, name VARCHAR(32))");
            for (int i = 1; i <= ROWS; i++) {
                st.execute("INSERT INTO " + SOURCE_DB + "." + TABLE + " VALUES ('2026-01-01 00:00:0"
                        + i + ".000', " + i + ", 'evt-" + i + "')");
            }
            st.execute("CREATE STABLE " + SOURCE_DB + ".meters (ts TIMESTAMP, val DOUBLE)"
                    + " TAGS (sn VARCHAR(32))");
            st.execute("CREATE TABLE " + SOURCE_DB + ".m1 USING " + SOURCE_DB
                    + ".meters TAGS ('sn-1')");
        }
    }

    private Project savedProject(String name, String table, String cursorColumn) {
        return savedProject(name, table, cursorColumn, false);
    }

    private Project savedProject(String name, String table, String cursorColumn,
                                 boolean syncDeletes) {
        DatabaseConfig source = savedConfig("td-it-src", DatabaseType.TDENGINE, SOURCE_DB,
                SOURCE_DB, TDENGINE.getHost(), TDENGINE.getMappedPort(6041),
                ConnectionRole.SOURCE);
        DatabaseConfig target = savedConfig("td-it-tgt", DatabaseType.POSTGRESQL, "tgt",
                "public", POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                ConnectionRole.TARGET);
        SyncConfig config = new SyncConfig();
        // Explicit: TDengine has no auto-detected cursor column name.
        config.setCursorColumns(Map.of(table, cursorColumn));
        config.setSyncDeletes(syncDeletes);
        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(false);
        return projectRepository.save(project);
    }

    private Connection sourceConnection() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:TAOS-WS://" + TDENGINE.getHost() + ":"
                        + TDENGINE.getMappedPort(6041) + "/" + SOURCE_DB, USER, PASSWORD);
    }

    private DatabaseConfig savedConfig(String name, DatabaseType type, String database,
            String schema, String host, int port, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(type);
        config.setRole(role);
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(database);
        config.setSchemaName(schema);
        config.setUsername(type == DatabaseType.TDENGINE ? USER : "test");
        config.setPassword(cryptoUtil.encrypt(type == DatabaseType.TDENGINE ? PASSWORD : "test"));
        return databaseConfigRepository.save(config);
    }

    private long targetRowCount() throws Exception {
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM public.events")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private java.util.List<String> targetPrimaryKey() throws Exception {
        return targetPrimaryKey("events");
    }

    private java.util.List<String> targetPrimaryKey(String table) throws Exception {
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT a.attname FROM pg_index i"
                             + " JOIN pg_attribute a ON a.attrelid = i.indrelid"
                             + " AND a.attnum = ANY(i.indkey)"
                             + " WHERE i.indrelid = 'public." + table
                             + "'::regclass AND i.indisprimary"
                             + " ORDER BY array_position(i.indkey, a.attnum)")) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
            return keys;
        }
    }

    private Map<String, String> targetTypes() throws Exception {
        Map<String, String> types = new LinkedHashMap<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_name, data_type FROM information_schema.columns"
                             + " WHERE table_schema = 'public' AND table_name = 'events'")) {
            while (rs.next()) {
                types.put(rs.getString(1), rs.getString(2));
            }
        }
        return types;
    }

    private java.util.List<String> targetNames(String table) throws Exception {
        java.util.List<String> values = new java.util.ArrayList<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM public." + table + " ORDER BY id")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private java.util.List<java.sql.Timestamp> sourceMeterTimestamps() throws Exception {
        java.util.List<java.sql.Timestamp> values = new java.util.ArrayList<>();
        try (Connection conn = sourceConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ts FROM meters ORDER BY ts")) {
            while (rs.next()) {
                values.add(rs.getTimestamp(1));
            }
        }
        return values;
    }

    private java.util.List<java.sql.Timestamp> targetMeterTimestamps() throws Exception {
        java.util.List<java.sql.Timestamp> values = new java.util.ArrayList<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ts FROM public.meters ORDER BY ts")) {
            while (rs.next()) {
                values.add(rs.getTimestamp(1));
            }
        }
        return values;
    }

    /** The tag column of a super table arrives as an ordinary target column. */
    private java.util.List<String> targetMeterNames() throws Exception {
        java.util.List<String> values = new java.util.ArrayList<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT sn FROM public.meters ORDER BY ts")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private java.util.List<String> targetValues() throws Exception {
        java.util.List<String> values = new java.util.ArrayList<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM public.events ORDER BY id")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private Connection targetConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "test", "test");
    }
}
