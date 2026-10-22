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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * 60 × (≈4.2 MB {@code text} + 1 MB {@code bytea}) from PostgreSQL into Oracle's
 * {@code CLOB} / {@code BLOB} — over 300 MB of source data through a 96 MB heap — first
 * through the baseline INSERT and then through the incremental MERGE.
 *
 * <p>The size of the data set, not the size of one value, is the point: it fails if the
 * read path accumulates the result set, the batch or the transaction. Two honest caveats
 * shape the shape of the test:
 * <ul>
 *   <li>pgjdbc decodes a text value into a {@code String} inside
 *       {@code getCharacterStream}, so one value is always materialized; the heap must hold
 *       the largest single value (roughly 2-3x its UTF-8 size). 20 MB values need ~64 MB and
 *       cannot be exercised under 96 MB.</li>
 *   <li>With a fetch size, pgjdbc holds up to that many rows' raw bytes. The test therefore
 *       uses {@code full-load-fetch-size=1}: a production PG source with multi-megabyte
 *       values must lower that knob too, which the README states.</li>
 * </ul>
 * The incremental window still binds through an Oracle temporary LOB — the path that used to
 * raise {@code ORA-01461} above 32767 bytes. Verification reads only lengths and short
 * substrings via {@code DBMS_LOB}, so the assertions never materialize a value either.
 *
 * <p>Gated by {@code -Djync.lob.memoryIT=true}; {@code -Plob-memory-it} sets the small heap.
 * Docker required.
 * <pre>
 * mvn test -Dtest=PostgresToOracleLobIT -Djync.lob.memoryIT=true -Plob-memory-it \
 *     -Dapi.version=1.44
 * </pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "jync.lob.memoryIT", matches = "true")
class PostgresToOracleLobIT {

    private static final String SRC_TABLE = "lob_src";
    private static final String TGT_TABLE = "lob_src";

    private static final int ROWS = 60;
    private static final int ROW_TEXT_CHARS = 1_400_000;         // ≈ 4.2 MB of UTF-8
    private static final int ROW_BLOB_BYTES = 1024 * 1024;

    @Container
    private static final GenericContainer<?> PG =
            new GenericContainer<>(DockerImageName.parse("postgres:16"))
                    .withEnv("POSTGRES_USER", "test")
                    .withEnv("POSTGRES_PASSWORD", "test")
                    .withEnv("POSTGRES_DB", "test")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(java.time.Duration.ofMinutes(3)));

    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    private static String oracleSchema() {
        return ORACLE.getUsername().toUpperCase(Locale.ROOT);
    }

    @Test
    void threeHundredMegabytesOfLobsCrossABaselineAndAnIncrementalMergeInNinetySixMegabytes()
            throws SQLException {
        prepareSource();
        prepareTarget();

        DataSyncService.TableSyncResult baseline = sync(new SyncProgress());
        assertThat(baseline.isSuccess()).as(baseline.getError()).isTrue();

        for (long id : new long[] {1L, ROWS / 2, ROWS}) {
            assertThat(clobLength(id)).as("CLOB length of row %s", id)
                    .isEqualTo(ROW_TEXT_CHARS);
            assertThat(blobLength(id)).as("BLOB length of row %s", id)
                    .isEqualTo(ROW_BLOB_BYTES);
        }
        assertThat(clobPrefix(1, 3)).isEqualTo("大大大");
        assertThat(blobPrefixHex(1, 4)).isEqualTo("ABABABAB");

        // An incremental window: the new row is written through MERGE, which is the exact
        // bind path that used to raise ORA-01461 above 32767 bytes.
        insertSourceRow(ROWS + 1L, ROW_TEXT_CHARS, ROW_BLOB_BYTES);
        DataSyncService.TableSyncResult incremental = sync(progressAfterBaseline());
        assertThat(incremental.isSuccess()).as(incremental.getError()).isTrue();

        assertThat(clobLength(ROWS + 1L)).isEqualTo(ROW_TEXT_CHARS);
        assertThat(clobPrefix(ROWS + 1L, 3)).isEqualTo("大大大");
        assertThat(blobLength(ROWS + 1L)).isEqualTo(ROW_BLOB_BYTES);
        assertThat(blobPrefixHex(ROWS + 1L, 4)).isEqualTo("ABABABAB");
        // The baseline rows are untouched by the incremental MERGE.
        assertThat(clobLength(1)).isEqualTo(ROW_TEXT_CHARS);
        assertThat(blobLength(1)).isEqualTo(ROW_BLOB_BYTES);
    }

    private SyncProperties properties() {
        SyncProperties properties = new SyncProperties();
        // Force the streaming path: anything above 1 KB leaves the heap behind.
        properties.getLob().setInlineMaxBytes(1024L);
        properties.setChunkSize(0);          // one streamed read; chunking would hide it
        properties.setFullLoadParallelism(1);
        properties.setFullLoadBatchSize(1);
        // pgjdbc holds up to fetch-size rows' raw bytes; one row at a time keeps a
        // 300 MB table inside a 96 MB heap. See the class Javadoc.
        properties.setFullLoadFetchSize(1);
        return properties;
    }

    private DataSyncService.TableSyncResult sync(SyncProgress progress) throws SQLException {
        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(new LinkedHashMap<>(Map.of(SRC_TABLE, TGT_TABLE)));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(31L);
        sourceCfg.setType(DatabaseType.POSTGRESQL);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(32L);
        targetCfg.setType(DatabaseType.ORACLE);

        Project project = new Project();
        project.setId(31L);
        project.setName("pg-oracle-lob-it");

        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.POSTGRESQL)
                .targetType(DatabaseType.ORACLE)
                .sourceSchema("public")
                .targetSchema(oracleSchema())
                .sourceDialect(new PostgresDialect())
                .targetDialect(new OracleDialect())
                .batchSize(1)
                .fullLoadBatchSize(1)
                .fullLoadFetchSize(1)
                .fullLoadParallelism(1)
                .connectionProvider(cfg -> cfg.getType() == DatabaseType.POSTGRESQL
                        ? pgConnection() : oracleConnection())
                .build();

        CursorStrategy strategy = CursorStrategy.identity("id", Types.BIGINT,
                "bigint primary key");

        DataSyncService service = new DataSyncService(properties(),
                mock(ChunkCheckpointRepository.class));
        try (Connection sourceConn = pgConnection();
             Connection targetConn = oracleConnection()) {
            return service.syncTable(sourceConn, targetConn, sourceMeta(), strategy,
                    progress, ctx);
        }
    }

    private SyncProgress progressAfterBaseline() {
        SyncProgress progress = new SyncProgress();
        progress.setCursorColumn("id");
        progress.setLastSyncValue(Integer.toString(ROWS));
        return progress;
    }

    private TableMeta sourceMeta() {
        TableMeta meta = new TableMeta();
        meta.setName(SRC_TABLE);
        meta.setSchema("public");
        meta.setColumns(List.of(
                column("id", "int8", Types.BIGINT, 0),
                column("c_text", "text", Types.VARCHAR, Integer.MAX_VALUE),
                column("c_bytea", "bytea", Types.BINARY, Integer.MAX_VALUE)));
        meta.setPrimaryKeys(List.of("id"));
        return meta;
    }

    private ColumnMeta column(String name, String typeName, int jdbcType, int size) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setTypeName(typeName);
        column.setJdbcType(jdbcType);
        column.setSize(size);
        column.setNullable(name.equals("id") ? false : true);
        column.setPrimaryKey(name.equals("id"));
        return column;
    }

    private void prepareSource() throws SQLException {
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + SRC_TABLE);
            st.execute("CREATE TABLE " + SRC_TABLE
                    + " (id bigint PRIMARY KEY, c_text text, c_bytea bytea)");
            // Built on the server so neither this JVM nor the parameter path ever holds a value.
            st.execute("INSERT INTO " + SRC_TABLE
                    + " SELECT g, repeat('大', " + ROW_TEXT_CHARS + "),"
                    + " decode(repeat('ab', " + ROW_BLOB_BYTES + "), 'hex')"
                    + " FROM generate_series(1, " + ROWS + ") AS g");
        }
    }

    private void insertSourceRow(long id, int textChars, int blobBytes) throws SQLException {
        // The value is built on the server: the JVM never holds it, and neither does the
        // driver's parameter path.
        String sql = "INSERT INTO " + SRC_TABLE + " VALUES (" + id
                + ", repeat('大', " + textChars + ")"
                + ", decode(repeat('ab', " + blobBytes + "), 'hex'))";
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void prepareTarget() throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement()) {
            try {
                st.execute("DROP TABLE \"LOB_SRC\" PURGE");
            } catch (SQLException ignored) {
                // First run.
            }
            // OracleDialect folds bare identifiers to upper case, so the writer targets
            // "LOB_SRC"."ID"/"C_TEXT"/"C_BYTEA" regardless of the source's spelling.
            st.execute("CREATE TABLE \"LOB_SRC\" (\"ID\" NUMBER(19) PRIMARY KEY,"
                    + " \"C_TEXT\" CLOB, \"C_BYTEA\" BLOB)");
        }
    }

    private long clobLength(long id) throws SQLException {
        return scalarLong("SELECT DBMS_LOB.GETLENGTH(\"C_TEXT\") FROM \"LOB_SRC\""
                + " WHERE \"ID\" = " + id);
    }

    private long blobLength(long id) throws SQLException {
        return scalarLong("SELECT DBMS_LOB.GETLENGTH(\"C_BYTEA\") FROM \"LOB_SRC\""
                + " WHERE \"ID\" = " + id);
    }

    private String clobPrefix(long id, int chars) throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DBMS_LOB.SUBSTR(\"C_TEXT\", " + chars
                     + ", 1) FROM \"LOB_SRC\" WHERE \"ID\" = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String blobPrefixHex(long id, int bytes) throws SQLException {
        try (Connection conn = oracleConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT RAWTOHEX(DBMS_LOB.SUBSTR(\"C_BYTEA\", "
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

    private static Connection pgConnection() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/test",
                "test", "test");
    }

    private static Connection oracleConnection() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }
}
