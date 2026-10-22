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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlServerDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * SQL Server 2022 → Oracle 23ai：{@code NVARCHAR(MAX)} / {@code VARBINARY(MAX)} 大字段的
 * 全量基线（空表 INSERT-first）与增量 upsert。
 *
 * <p>补齐“SQL Server 源 → Oracle 目标”这一格。值超过 Oracle 的 32767 字节绑定上限，
 * 增量写入必须走临时 CLOB/BLOB；{@code inline-max-bytes=1024} 让读端走
 * {@code getCharacterStream}/{@code getBinaryStream}，不把整值留在堆里。校验只用
 * {@code DBMS_LOB} 取长度与头尾片段。
 *
 * <p>SQL Server 镜像启动慢（首启还要建 master 系统库），启动超时给到 5 分钟，并在
 * {@link #awaitReady()} 里等真正的登录成功；无 Docker 时整类跳过。运行：
 * {@code mvn test -Dtest=SqlServerToOracleLobIT -Dapi.version=1.44}。
 */
@Testcontainers(disabledWithoutDocker = true)
class SqlServerToOracleLobIT {

    private static final String PASSWORD = "Jync#test123";
    private static final String SRC_TABLE = "lob_src";

    private static final int BIG_TEXT_CHARS = 200_000;   // ≈ 600 KB of UTF-8
    private static final int BIG_BLOB_BYTES = 1_000_000;
    /** 40 000 汉字 = 120 000 字节，超过 Oracle 的 32767 字节绑定上限。 */
    private static final int MEDIUM_TEXT_CHARS = 40_000;

    @Container
    private static final GenericContainer<?> MSSQL =
            new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .withEnv("ACCEPT_EULA", "Y")
                    .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
                    .withExposedPorts(1433)
                    .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    private static String oracleSchema() {
        return ORACLE.getUsername().toUpperCase(Locale.ROOT);
    }

    @BeforeAll
    static void prepareSource() throws SQLException {
        awaitReady();
        try (Connection conn = mssql(); Statement st = conn.createStatement()) {
            st.execute("IF OBJECT_ID('" + SRC_TABLE + "', 'U') IS NOT NULL"
                    + " DROP TABLE " + SRC_TABLE);
            st.execute("CREATE TABLE " + SRC_TABLE
                    + " (id BIGINT PRIMARY KEY, c_text NVARCHAR(MAX), c_bin VARBINARY(MAX))");
        }
        // Values go through real LOB streams: a T-SQL literal caps at 8000 bytes.
        insertSourceRow(1, BIG_TEXT_CHARS, BIG_BLOB_BYTES, '中', (byte) 0xab);
        insertSourceRow(2, 5, 4, 'a', (byte) 0x01);
        insertSourceRow(3, MEDIUM_TEXT_CHARS, 4_096, '文', (byte) 0xcd);
    }

    @Test
    void largeLobsSurviveBaselineAndIncrementalUpsert() throws SQLException {
        prepareTarget();

        DataSyncService.TableSyncResult baseline = sync(new SyncProgress());
        assertThat(baseline.isSuccess()).as(baseline.getError()).isTrue();

        assertThat(clobLength(1)).isEqualTo(BIG_TEXT_CHARS);
        assertThat(clobPrefix(1, 3)).isEqualTo("中中中");
        assertThat(clobSuffix(1, 3)).isEqualTo("中中中");
        assertThat(blobLength(1)).isEqualTo(BIG_BLOB_BYTES);
        assertThat(blobPrefixHex(1, 4)).isEqualTo("ABABABAB");

        assertThat(clobLength(3)).isEqualTo(MEDIUM_TEXT_CHARS);
        assertThat(clobPrefix(3, 3)).isEqualTo("文文文");

        // Incremental window: the new row is written through Oracle's MERGE, the path that
        // used to raise ORA-01461 above 32767 bytes.
        insertSourceRow(4, MEDIUM_TEXT_CHARS, 500_000, '字', (byte) 0xcd);
        DataSyncService.TableSyncResult incremental = sync(progressAfterBaseline());
        assertThat(incremental.isSuccess()).as(incremental.getError()).isTrue();

        assertThat(clobLength(4)).isEqualTo(MEDIUM_TEXT_CHARS);
        assertThat(clobPrefix(4, 3)).isEqualTo("字字字");
        assertThat(clobSuffix(4, 3)).isEqualTo("字字字");
        assertThat(blobLength(4)).isEqualTo(500_000);
        assertThat(blobPrefixHex(4, 4)).isEqualTo("CDCDCDCD");

        // The baseline rows are untouched by the incremental MERGE.
        assertThat(clobLength(1)).isEqualTo(BIG_TEXT_CHARS);
        assertThat(blobLength(1)).isEqualTo(BIG_BLOB_BYTES);
    }

    private SyncProperties properties() {
        SyncProperties properties = new SyncProperties();
        properties.getLob().setInlineMaxBytes(1024L);
        properties.setChunkSize(0);
        properties.setFullLoadParallelism(1);
        properties.setFullLoadBatchSize(1);
        properties.setFullLoadFetchSize(1);
        return properties;
    }

    private DataSyncService.TableSyncResult sync(SyncProgress progress) throws SQLException {
        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(new LinkedHashMap<>(Map.of(SRC_TABLE, SRC_TABLE)));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(61L);
        sourceCfg.setType(DatabaseType.SQLSERVER);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(62L);
        targetCfg.setType(DatabaseType.ORACLE);

        Project project = new Project();
        project.setId(61L);
        project.setName("mssql-oracle-lob-it");

        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.SQLSERVER)
                .targetType(DatabaseType.ORACLE)
                .sourceSchema("dbo")
                .targetSchema(oracleSchema())
                .sourceDialect(new SqlServerDialect())
                .targetDialect(new OracleDialect())
                .batchSize(1)
                .fullLoadBatchSize(1)
                .fullLoadFetchSize(1)
                .fullLoadParallelism(1)
                .connectionProvider(cfg -> cfg.getType() == DatabaseType.SQLSERVER
                        ? mssql() : oracleConnection())
                .build();

        CursorStrategy strategy = CursorStrategy.identity("id", Types.BIGINT,
                "bigint primary key");

        DataSyncService service = new DataSyncService(properties(),
                mock(ChunkCheckpointRepository.class));
        try (Connection sourceConn = mssql();
             Connection targetConn = oracleConnection()) {
            return service.syncTable(sourceConn, targetConn, sourceMeta(), strategy,
                    progress, ctx);
        }
    }

    private SyncProgress progressAfterBaseline() {
        SyncProgress progress = new SyncProgress();
        progress.setCursorColumn("id");
        progress.setLastSyncValue("3");
        return progress;
    }

    private TableMeta sourceMeta() {
        TableMeta meta = new TableMeta();
        meta.setName(SRC_TABLE);
        meta.setSchema("dbo");
        meta.setColumns(List.of(
                column("id", "bigint", Types.BIGINT, 19),
                column("c_text", "nvarchar(max)", Types.LONGNVARCHAR, Integer.MAX_VALUE),
                column("c_bin", "varbinary(max)", Types.LONGVARBINARY, Integer.MAX_VALUE)));
        meta.setPrimaryKeys(List.of("id"));
        return meta;
    }

    private ColumnMeta column(String name, String typeName, int jdbcType, int size) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setTypeName(typeName);
        column.setJdbcType(jdbcType);
        column.setSize(size);
        column.setNullable(!"id".equals(name));
        column.setPrimaryKey("id".equals(name));
        return column;
    }

    private static void insertSourceRow(long id, int textChars, int blobBytes, char textChar,
                                        byte blobByte) throws SQLException {
        String text = String.valueOf(textChar).repeat(textChars);
        byte[] blob = new byte[blobBytes];
        java.util.Arrays.fill(blob, blobByte);
        try (Connection conn = mssql();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + SRC_TABLE + " (id, c_text, c_bin) VALUES (?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setNCharacterStream(2, new StringReader(text), text.length());
            ps.setBinaryStream(3, new ByteArrayInputStream(blob), blob.length);
            ps.executeUpdate();
        }
    }

    private void prepareTarget() throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement()) {
            try {
                st.execute("DROP TABLE \"LOB_SRC\" PURGE");
            } catch (SQLException ignored) {
                // First run.
            }
            // OracleDialect folds bare identifiers to upper case (source spells them lower
            // case), so the writer targets "LOB_SRC"."ID"/"C_TEXT"/"C_BIN".
            st.execute("CREATE TABLE \"LOB_SRC\" (\"ID\" NUMBER(19) PRIMARY KEY,"
                    + " \"C_TEXT\" CLOB, \"C_BIN\" BLOB)");
        }
    }

    private long clobLength(long id) throws SQLException {
        return scalarLong("SELECT DBMS_LOB.GETLENGTH(\"C_TEXT\") FROM \"LOB_SRC\""
                + " WHERE \"ID\" = " + id);
    }

    private long blobLength(long id) throws SQLException {
        return scalarLong("SELECT DBMS_LOB.GETLENGTH(\"C_BIN\") FROM \"LOB_SRC\""
                + " WHERE \"ID\" = " + id);
    }

    private String clobPrefix(long id, int chars) throws SQLException {
        return lobText("DBMS_LOB.SUBSTR(\"C_TEXT\", " + chars + ", 1)", id);
    }

    private String clobSuffix(long id, int chars) throws SQLException {
        return lobText("DBMS_LOB.SUBSTR(\"C_TEXT\", " + chars + ", "
                + "DBMS_LOB.GETLENGTH(\"C_TEXT\") - " + (chars - 1) + ")", id);
    }

    private String lobText(String expression, long id) throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + expression + " FROM \"LOB_SRC\""
                     + " WHERE \"ID\" = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String blobPrefixHex(long id, int bytes) throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT RAWTOHEX(DBMS_LOB.SUBSTR(\"C_BIN\", "
                     + bytes + ", 1)) FROM \"LOB_SRC\" WHERE \"ID\" = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * The TCP port and the readiness log line can both appear while the instance still
     * refuses logins (first-boot system database setup), so acceptance is a real login.
     */
    private static void awaitReady() {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (true) {
            try (Connection ignored = mssql()) {
                return;
            } catch (SQLException notReady) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("SQL Server did not accept logins in 180s",
                            notReady);
                }
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for SQL Server", ie);
                }
            }
        }
    }

    private static Connection mssql() throws SQLException {
        String url = "jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=master;encrypt=false;trustServerCertificate=true";
        return DriverManager.getConnection(url, "sa", PASSWORD);
    }

    private static Connection oracleConnection() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }
}
