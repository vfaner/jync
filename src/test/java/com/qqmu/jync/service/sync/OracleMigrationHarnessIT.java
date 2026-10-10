package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.metadata.OracleMetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * The Phase A migration harness (task book M5): a real Oracle source proving the chunked
 * full-load stack end to end — parallel range split over an Oracle {@code NUMBER(18)} key,
 * checkpointed resume against the real dictionary, and the M4 {@code ORA_ROWSCN} polling
 * option on a table actually created {@code ROWDEPENDENCIES}, where it must see updates
 * that an IDENTITY cursor would miss forever.
 *
 * <p>Target is in-JVM H2: the migration direction this validates is Oracle → 信创库, and
 * the target side of that path (dialect, upsert, DDL) is the same code every other test
 * exercises; what only a real Oracle can prove is the source side. The Oracle → 达梦 leg
 * itself is a documented manual harness ({@code docs/migration-harness-oracle-to-dm.md}),
 * because 达梦 publishes no container image.
 *
 * <p>Named {@code *IT} so surefire's default includes skip it; run explicitly with
 * {@code mvn test -Dtest=OracleMigrationHarnessIT}. Without Docker the class disables
 * itself instead of failing.
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleMigrationHarnessIT {

    // 23ai Free rather than XE 21: XE has no arm64 build and the amd64 image fails with
    // ORA-00443 (PMON) under Apple-Silicon emulation. Everything asserted here — product
    // name "Oracle", NUMBER(18) keys, ROWDEPENDENCIES/ORA_ROWSCN — behaves the same.
    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"));

    private static final String TGT_URL = "jdbc:h2:mem:mig_tgt;DB_CLOSE_DELAY=-1";
    private static final int ROWS = 5000;

    private Connection source;
    private Connection target;
    private ChunkCheckpointRepository checkpoints;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = oracleConnection();
        target = DriverManager.getConnection(TGT_URL, "sa", "");
        checkpoints = mock(ChunkCheckpointRepository.class);

        sourceCfg = new DatabaseConfig();
        sourceCfg.setId(1L);
        sourceCfg.setType(DatabaseType.ORACLE);
        targetCfg = new DatabaseConfig();
        targetCfg.setId(2L);
        Project project = new Project();
        project.setId(7L);
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceDialect(new OracleDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .connectionProvider(cfg -> cfg == sourceCfg
                        ? oracleConnection()
                        : DriverManager.getConnection(TGT_URL, "sa", ""))
                .build();

        dropQuietly(source, "ITEMS");
        dropQuietly(source, "SCN_T");
        exec(target, "DROP ALL OBJECTS");
    }

    private static Connection oracleConnection() throws SQLException {
        return DriverManager.getConnection(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
    }

    // --- chunked + parallel full load off a real Oracle --------------------------------------

    @Test
    void parallelRangesCopyANumberKeyedOracleTableCompletely() throws SQLException {
        createItems(ROWS);
        h2Items();

        // The dictionary probe on the plain table: no ROWDEPENDENCIES, so no SCN cursor.
        TableMeta probed = new OracleMetadataReader().readTable(source, null, "ITEMS");
        assertThat(probed.getRowLevelScn()).isFalse();

        DataSyncService.TableSyncResult result = service(4, 500).syncTable(
                source, target, itemsMeta(), identityNumeric(), new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getRowsWritten()).isEqualTo(ROWS);
        assertThat(count("ITEMS")).isEqualTo(ROWS);
        assertThat(h2Long("SELECT SUM(ID) FROM ITEMS")).isEqualTo((long) ROWS * (ROWS + 1) / 2);
        assertThat(h2String("SELECT NAME FROM ITEMS WHERE ID = 4999")).isEqualTo("row-4999");
        assertThat(result.getNewCursorValue()).isEqualTo(String.valueOf(ROWS));
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");

        // Proof the load really split: workers beyond range 0 checkpointed their progress.
        ArgumentCaptor<ChunkCheckpoint> captor = ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeIndex() != null && c.getRangeIndex() > 0);
    }

    @Test
    void anInterruptedLoadResumesFromTheCheckpointOnRealOracle() throws SQLException {
        createItems(ROWS);
        h2Items();
        // Crash leftovers: chunks of 500 covered ids 1-2500, all committed on the target,
        // the newest checkpoint holding the keyset boundary at 2500.
        exec(target, "INSERT INTO ITEMS SELECT X, 'row-' || X FROM SYSTEM_RANGE(1, 2500)");
        when(checkpoints.findByProjectIdAndTableName(7L, "ITEMS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "ITEMS", 0, 4, "[2500]", 2500,
                        ChunkCheckpoint.STATUS_CHUNK)));

        DataSyncService.TableSyncResult result = service(1, 500).syncTable(
                source, target, itemsMeta(), identityNumeric(), new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Only the tail is re-read off Oracle; the checkpointed half is skipped.
        assertThat(result.getRowsWritten()).isEqualTo(ROWS - 2500);
        assertThat(count("ITEMS")).isEqualTo(ROWS);
        assertThat(h2Long("SELECT SUM(ID) FROM ITEMS")).isEqualTo((long) ROWS * (ROWS + 1) / 2);
        assertThat(result.getNewCursorValue()).isEqualTo(String.valueOf(ROWS));
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    // --- ORA_ROWSCN polling on a real ROWDEPENDENCIES table ----------------------------------

    @Test
    void rowScnPollingSeesInsertsAndUpdatesOnRealOracle() throws SQLException {
        exec(source, "CREATE TABLE SCN_T (ID NUMBER(18) PRIMARY KEY, NAME VARCHAR2(50))"
                + " ROWDEPENDENCIES");
        exec(source, "INSERT INTO SCN_T VALUES (1, 'one')");
        exec(source, "INSERT INTO SCN_T VALUES (2, 'two')");
        exec(source, "INSERT INTO SCN_T VALUES (3, 'three')");
        exec(source, "COMMIT");
        exec(target, "CREATE TABLE SCN_T (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        // The whole M4 chain against the live dictionary: probe → resolver → ROW_SCN.
        TableMeta meta = new OracleMetadataReader().readTable(source, null, "SCN_T");
        assertThat(meta.getRowLevelScn()).isTrue();
        CursorStrategy strategy =
                new CursorStrategyResolver(new SyncProperties()).resolve(meta, new SyncConfig());
        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.ROW_SCN);

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed = service(1, 100).syncTable(
                source, target, meta, strategy, progress, ctx);
        assertThat(seed.isSuccess()).as(seed.getError()).isTrue();
        assertThat(count("SCN_T")).isEqualTo(3);
        assertThat(seed.getNewCursorValue()).isNotNull();

        // One insert and one update to an already-synced row, each in its own commit.
        exec(source, "INSERT INTO SCN_T VALUES (4, 'four')");
        exec(source, "COMMIT");
        exec(source, "UPDATE SCN_T SET NAME = 'one-updated' WHERE ID = 1");
        exec(source, "COMMIT");

        progress.setLastSyncValue(seed.getNewCursorValue());
        progress.setInitialLoadDone(true);
        DataSyncService.TableSyncResult cycle = service(1, 100).syncTable(
                source, target, meta, strategy, progress, ctx);

        assertThat(cycle.isSuccess()).as(cycle.getError()).isTrue();
        assertThat(count("SCN_T")).isEqualTo(4);
        assertThat(h2String("SELECT NAME FROM SCN_T WHERE ID = 4")).isEqualTo("four");
        // The money shot: an UPDATE to an existing row is delivered — the exact thing an
        // IDENTITY cursor on the same table could never see.
        assertThat(h2String("SELECT NAME FROM SCN_T WHERE ID = 1")).isEqualTo("one-updated");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private DataSyncService service(int parallelism, int chunkSize) {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(chunkSize);
        properties.setFullLoadParallelism(parallelism);
        return new DataSyncService(properties, checkpoints);
    }

    private void createItems(int rows) throws SQLException {
        // NUMBER(18), not 19: precision 19 can exceed Long.MAX_VALUE, and the range splitter
        // refuses keys whose span does not provably fit a long (falls back to sequential).
        exec(source, "CREATE TABLE ITEMS (ID NUMBER(18) PRIMARY KEY, NAME VARCHAR2(50))");
        exec(source, "BEGIN FOR i IN 1.." + rows + " LOOP"
                + " INSERT INTO ITEMS VALUES (i, 'row-' || i); END LOOP; COMMIT; END;");
    }

    private void h2Items() throws SQLException {
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
    }

    private static CursorStrategy identityNumeric() {
        return CursorStrategy.identity("ID", Types.NUMERIC, "numeric primary key");
    }

    /** Hand-built meta mirroring what the Oracle dictionary reports for NUMBER(18). */
    private static TableMeta itemsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.NUMERIC);
        id.setTypeName("NUMBER");
        id.setSize(18);
        id.setDecimalDigits(0);
        id.setPrimaryKey(true);
        id.setNullable(false);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        name.setTypeName("VARCHAR2");
        name.setSize(50);
        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setColumns(new ArrayList<>(List.of(id, name)));
        table.setPrimaryKeys(List.of("ID"));
        return table;
    }

    private long count(String table) throws SQLException {
        return h2Long("SELECT COUNT(*) FROM " + table);
    }

    private long h2Long(String sql) throws SQLException {
        try (Statement st = target.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private String h2String(String sql) throws SQLException {
        try (Statement st = target.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void dropQuietly(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE " + table);
        } catch (SQLException ignored) {
            // Not there yet — fine.
        }
    }
}
