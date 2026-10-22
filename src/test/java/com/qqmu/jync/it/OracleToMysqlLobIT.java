package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Oracle 23ai Free → MySQL 8：{@code CLOB} / {@code BLOB} 大字段的全量基线（目标空表
 * INSERT-first）与之后的增量 upsert。
 *
 * <p>这是矩阵里此前唯一空缺的“Oracle 源 → MySQL 目标”大字段方向。值都超过 Oracle 的
 * 32767 字节绑定上限（{@link #BIG_TEXT_CHARS} 个汉字 ≈ 600 KB），因此两条写入路径都必须
 * 走临时 LOB / 流式绑定；同时 {@code inline-max-bytes=1024} 强制读端走流式，值不会整块
 * 进入堆。校验只取长度、头尾片段，不把整值读回 JVM。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it — run explicitly:
 * {@code mvn test -Dtest=OracleToMysqlLobIT -Dapi.version=1.44}。Docker required。
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleToMysqlLobIT {

    private static final String TABLE = "LOB_SRC";

    private static final int BIG_TEXT_CHARS = 200_000;   // ≈ 600 KB of UTF-8
    private static final int BIG_BLOB_BYTES = 1_000_000;
    /** 40 000 汉字 = 120 000 字节，超过 Oracle 的 32767 字节绑定上限。 */
    private static final int MEDIUM_TEXT_CHARS = 40_000;

    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            // A 600 KB CLOB and a 1 MB BLOB in one row exceed the server's 1 MB default;
            // production sources need this raised too, which the README states.
            .withCommand("--max_allowed_packet=67108864");

    @Test
    void largeLobsSurviveBaselineAndIncrementalUpsert() throws SQLException {
        prepareSource();
        prepareTarget();

        DataSyncService.TableSyncResult baseline = sync(new SyncProgress());
        assertThat(baseline.isSuccess()).as(baseline.getError()).isTrue();

        Map<String, Object> big = mysqlRow(1);
        assertThat(((Number) big.get("chars")).longValue()).isEqualTo(BIG_TEXT_CHARS);
        assertThat(((Number) big.get("octets")).longValue()).isEqualTo(BIG_TEXT_CHARS * 3);
        assertThat(big.get("head")).isEqualTo("中中中");
        assertThat(big.get("tail")).isEqualTo("中中中");
        assertThat(((Number) big.get("bin_len")).longValue()).isEqualTo(BIG_BLOB_BYTES);
        assertThat(big.get("bin_head")).isEqualTo("ABABABAB");

        Map<String, Object> medium = mysqlRow(3);
        assertThat(((Number) medium.get("chars")).longValue()).isEqualTo(MEDIUM_TEXT_CHARS);
        assertThat(medium.get("head")).isEqualTo("文文文");

        // Incremental window: the new row is written through the upsert path, which for
        // Oracle sources has to stream a >32767-byte value without a LONG placeholder.
        insertSourceRow(4, MEDIUM_TEXT_CHARS, 500_000, '字', (byte) 0xcd);
        DataSyncService.TableSyncResult incremental = sync(progressAfterBaseline());
        assertThat(incremental.isSuccess()).as(incremental.getError()).isTrue();

        Map<String, Object> streamed = mysqlRow(4);
        assertThat(((Number) streamed.get("chars")).longValue()).isEqualTo(MEDIUM_TEXT_CHARS);
        assertThat(streamed.get("head")).isEqualTo("字字字");
        assertThat(streamed.get("tail")).isEqualTo("字字字");
        assertThat(((Number) streamed.get("bin_len")).longValue()).isEqualTo(500_000);
        assertThat(streamed.get("bin_head")).isEqualTo("CDCDCDCD");

        // The baseline rows are untouched by the incremental pass.
        assertThat(((Number) mysqlRow(1).get("chars")).longValue()).isEqualTo(BIG_TEXT_CHARS);
        assertThat(((Number) mysqlRow(1).get("bin_len")).longValue()).isEqualTo(BIG_BLOB_BYTES);
    }

    private SyncProperties properties() {
        SyncProperties properties = new SyncProperties();
        // Force the streaming path: anything above 1 KB leaves the heap behind.
        properties.getLob().setInlineMaxBytes(1024L);
        properties.setChunkSize(0);
        properties.setFullLoadParallelism(1);
        properties.setFullLoadBatchSize(1);
        properties.setFullLoadFetchSize(1);
        return properties;
    }

    private DataSyncService.TableSyncResult sync(SyncProgress progress) throws SQLException {
        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(new LinkedHashMap<>(Map.of(TABLE, TABLE)));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(51L);
        sourceCfg.setType(DatabaseType.ORACLE);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(52L);
        targetCfg.setType(DatabaseType.MYSQL);

        Project project = new Project();
        project.setId(51L);
        project.setName("oracle-mysql-lob-it");

        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.ORACLE)
                .targetType(DatabaseType.MYSQL)
                .sourceSchema(ORACLE.getUsername().toUpperCase(Locale.ROOT))
                .targetSchema(MYSQL.getDatabaseName())
                .sourceDialect(new OracleDialect())
                .targetDialect(new MySqlDialect())
                .batchSize(1)
                .fullLoadBatchSize(1)
                .fullLoadFetchSize(1)
                .fullLoadParallelism(1)
                .connectionProvider(cfg -> cfg.getType() == DatabaseType.ORACLE
                        ? oracleConnection() : mysqlConnection())
                .build();

        CursorStrategy strategy = CursorStrategy.identity("ID", Types.NUMERIC,
                "NUMBER(19) primary key");

        DataSyncService service = new DataSyncService(properties(),
                mock(ChunkCheckpointRepository.class));
        try (Connection sourceConn = oracleConnection();
             Connection targetConn = mysqlConnection()) {
            return service.syncTable(sourceConn, targetConn, sourceMeta(), strategy,
                    progress, ctx);
        }
    }

    private SyncProgress progressAfterBaseline() {
        SyncProgress progress = new SyncProgress();
        progress.setCursorColumn("ID");
        progress.setLastSyncValue("3");
        return progress;
    }

    private TableMeta sourceMeta() {
        TableMeta meta = new TableMeta();
        meta.setName(TABLE);
        meta.setSchema(ORACLE.getUsername().toUpperCase(Locale.ROOT));
        meta.setColumns(List.of(
                column("ID", "NUMBER(19)", Types.NUMERIC, 19),
                column("C_TEXT", "CLOB", Types.CLOB, Integer.MAX_VALUE),
                column("C_BIN", "BLOB", Types.BLOB, Integer.MAX_VALUE)));
        meta.setPrimaryKeys(List.of("ID"));
        return meta;
    }

    private ColumnMeta column(String name, String typeName, int jdbcType, int size) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setTypeName(typeName);
        column.setJdbcType(jdbcType);
        column.setSize(size);
        column.setNullable(!"ID".equals(name));
        column.setPrimaryKey("ID".equals(name));
        return column;
    }

    private void prepareSource() throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement()) {
            st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + TABLE
                    + " PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;");
            st.execute("CREATE TABLE " + TABLE
                    + " (ID NUMBER(19) PRIMARY KEY, C_TEXT CLOB, C_BIN BLOB)");
        }
        // Values go through real LOB streams: an Oracle SQL literal caps at 4000 bytes.
        insertSourceRow(1, BIG_TEXT_CHARS, BIG_BLOB_BYTES, '中', (byte) 0xab);
        insertSourceRow(2, 5, 4, 'a', (byte) 0x01);
        insertSourceRow(3, MEDIUM_TEXT_CHARS, 4_096, '文', (byte) 0xcd);
    }

    private void insertSourceRow(long id, int textChars, int blobBytes, char textChar,
                                 byte blobByte) throws SQLException {
        String text = String.valueOf(textChar).repeat(textChars);
        byte[] blob = new byte[blobBytes];
        java.util.Arrays.fill(blob, blobByte);
        try (Connection conn = oracleConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + TABLE + " (ID, C_TEXT, C_BIN) VALUES (?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setCharacterStream(2, new StringReader(text), text.length());
            ps.setBinaryStream(3, new ByteArrayInputStream(blob), blob.length);
            ps.executeUpdate();
        }
    }

    private void prepareTarget() throws SQLException {
        try (Connection conn = mysqlConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE
                    + " (ID BIGINT PRIMARY KEY, C_TEXT LONGTEXT, C_BIN LONGBLOB)");
        }
    }

    private Map<String, Object> mysqlRow(long id) throws SQLException {
        try (Connection conn = mysqlConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CHAR_LENGTH(C_TEXT) AS chars,"
                     + " OCTET_LENGTH(C_TEXT) AS octets,"
                     + " LEFT(C_TEXT, 3) AS head, RIGHT(C_TEXT, 3) AS tail,"
                     + " OCTET_LENGTH(C_BIN) AS bin_len,"
                     + " HEX(LEFT(C_BIN, 4)) AS bin_head"
                     + " FROM " + TABLE + " WHERE ID = " + id)) {
            assertThat(rs.next()).as("row %s must exist", id).isTrue();
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : new String[] {"chars", "octets", "head", "tail",
                    "bin_len", "bin_head"}) {
                row.put(column, rs.getObject(column));
            }
            return row;
        }
    }

    private static Connection oracleConnection() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }

    private static Connection mysqlConnection() throws SQLException {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + MYSQL.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }
}
