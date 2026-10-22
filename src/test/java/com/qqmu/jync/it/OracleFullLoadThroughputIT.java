package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
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
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.metadata.OracleMetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.JdbcRowMapper;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Evidence run for the initial-full-load throughput work: how fast this stack actually
 * writes 500k rows into Oracle, and where the time goes.
 *
 * <p>Three questions the numbers must answer, because each changes what the product should
 * do — not what it should look like:
 *
 * <ol>
 *   <li>Does the driver turn the batched single-row {@code MERGE} into array DML at all, and
 *       how much does the batch size matter? If batch size is irrelevant, the write loop is
 *       doing a round trip per row and no amount of tuning the knob will help.</li>
 *   <li>Is a plain {@code INSERT} (the exact shape an empty Oracle target accepts) enough
 *       faster than {@code MERGE} to justify a separate initial-load statement? Phase 6 is
 *       only worth doing above a 30% gap.</li>
 *   <li>Does the engine's parallel chunked load scale, and does it stay correct?</li>
 * </ol>
 *
 * <p>Only correctness is asserted. Timings are printed as a table for comparison across
 * runs; asserting on them would make the build fail on a slow machine for no reason.
 *
 * <p>Named {@code *IT} so surefire skips it by default; run explicitly with
 * {@code mvn test -Dtest=OracleFullLoadThroughputIT}. Without Docker the class disables
 * itself instead of failing. One container, one schema, distinct source and target tables —
 * the schema boundary does not change what a MERGE costs per row, and every other Oracle IT
 * in this suite already writes target tables in the container's own schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 60, unit = TimeUnit.MINUTES)
class OracleFullLoadThroughputIT {

    // 23ai Free rather than XE 21: XE has no arm64 build, and the amd64 image dies under
    // Apple-Silicon emulation. Same rule as every other Oracle IT here.
    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"));

    /** Large enough that the 100k chunk size splits into five chunks, so parallelism is real. */
    private static final int ROWS = 500_000;
    private static final int CHUNK_SIZE = 100_000;
    private static final int FULL_LOAD_BATCH = 2_000;
    private static final int FULL_LOAD_FETCH = 2_000;
    /** Live-traffic batch: the incremental and CDC paths keep the conservative default. */
    private static final int INCREMENTAL_BATCH = 500;
    /** Conservative read-side fetch for the raw-JDBC control runs. */
    private static final int CONTROL_FETCH = 1_000;

    private static final String SRC = "LOAD_SRC";
    private static final String DDL = " (ID NUMBER(18) PRIMARY KEY, NAME VARCHAR2(50),"
            + " AMOUNT NUMBER(12,2), CREATED DATE, MEMO VARCHAR2(200), QTY NUMBER(10),"
            + " STATUS VARCHAR2(20), UPDATED TIMESTAMP, CODE VARCHAR2(40), SCORE NUMBER(6,2))";

    private static String schema;
    private static String driverBanner;
    private static String oracleBatchProperties;

    @BeforeAll
    static void prepare() throws SQLException {
        schema = ORACLE.getUsername().toUpperCase(Locale.ROOT);
        try (Connection conn = open()) {
            DatabaseMetaData md = conn.getMetaData();
            driverBanner = md.getDriverName() + " " + md.getDriverVersion();
            Properties sys = System.getProperties();
            List<String> batchProps = new ArrayList<>();
            for (String name : sys.stringPropertyNames()) {
                if (name.startsWith("oracle.jdbc.")) {
                    batchProps.add(name + "=" + sys.getProperty(name));
                }
            }
            batchProps.sort(String::compareTo);
            oracleBatchProperties = batchProps.isEmpty() ? "(none set)" : String.join(", ", batchProps);
            createSource(conn);
        }
        System.out.println();
        System.out.println("Oracle benchmark environment");
        System.out.println("  driver      : " + driverBanner);
        System.out.println("  oracle.jdbc.: " + oracleBatchProperties);
    }

    // --- 1) raw JDBC: MERGE vs INSERT, batch sizes, index placement -------------------------

    @Test
    void rawJdbcWriteThroughput() throws SQLException {
        long scanNanos = scanOnly();
        List<Run> runs = new ArrayList<>();

        for (int batch : new int[] {500, 2000, 5000}) {
            String table = "LOAD_M" + batch;
            recreate("LOAD_M" + batch);
            runs.add(copy(runs.size() + 1, "MERGE batch=" + batch, table, false, batch));
        }
        recreate("LOAD_I2000");
        runs.add(copy(runs.size() + 1, "INSERT batch=2000", "LOAD_I2000", true, 2000));

        // Index placement: same MERGE, same batch, but the two secondary indexes exist while
        // the rows arrive (index-before) versus afterwards (index-after).
        recreate("LOAD_X2000");
        exec("CREATE INDEX IX_LOAD_X2000_NAME ON LOAD_X2000 (NAME)");
        exec("CREATE INDEX IX_LOAD_X2000_QTY ON LOAD_X2000 (QTY)");
        runs.add(copy(runs.size() + 1, "MERGE batch=2000, indexes pre-created",
                "LOAD_X2000", false, 2000));

        long indexAfterStart = System.nanoTime();
        exec("CREATE INDEX IX_LOAD_M2000_NAME ON LOAD_M2000 (NAME)");
        exec("CREATE INDEX IX_LOAD_M2000_QTY ON LOAD_M2000 (QTY)");
        long indexAfterNanos = System.nanoTime() - indexAfterStart;

        for (Run run : runs) {
            assertCopyIsCorrect(run);
        }
        assertThat((long) ROWS * (ROWS + 1) / 2).isEqualTo(checksum(SRC));

        printReport(scanNanos, runs, indexAfterNanos);
    }

    /**
     * The same read without any write. Reported so the write numbers can be read for what
     * they are: each control run also pays this scan, and a difference between two statements
     * smaller than the scan cost is not a difference the statement caused.
     */
    private long scanOnly() throws SQLException {
        long rows = 0;
        long start = System.nanoTime();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + SRC)) {
            ps.setFetchSize(CONTROL_FETCH);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = JdbcRowMapper.columnNames(md);
                List<Integer> types = JdbcRowMapper.columnTypes(md);
                List<String> typeNames = JdbcRowMapper.columnTypeNames(md);
                Map<String, Object> row = new LinkedHashMap<>();
                while (rs.next()) {
                    JdbcRowMapper.readRowInto(rs, columns, types, typeNames,
                            DatabaseType.ORACLE, row);
                    rows++;
                }
            }
        }
        assertThat(rows).isEqualTo(ROWS);
        return System.nanoTime() - start;
    }

    private Run copy(int index, String label, String targetTable,
                     boolean insertOnly, int batchSize) throws SQLException {
        OracleDialect dialect = new OracleDialect();
        int batchCalls = 0;
        int ones = 0;
        int zeros = 0;
        int noInfo = 0;
        int other = 0;
        int rows = 0;
        long start = System.nanoTime();
        try (Connection src = open(); Connection tgt = open()) {
            tgt.setAutoCommit(false);
            try (PreparedStatement select = src.prepareStatement("SELECT * FROM " + SRC)) {
                select.setFetchSize(CONTROL_FETCH);
                try (ResultSet rs = select.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> columns = JdbcRowMapper.columnNames(md);
                    List<Integer> jdbcTypes = JdbcRowMapper.columnTypes(md);
                    List<String> typeNames = JdbcRowMapper.columnTypeNames(md);
                    Map<String, Integer> typeByColumn = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        typeByColumn.put(columns.get(i), jdbcTypes.get(i));
                    }
                    List<String> keys = insertOnly ? List.of() : List.of("ID");
                    String sql = dialect.getUpsertSql(null, targetTable, columns, keys,
                            typeByColumn, false);
                    List<String> bindOrder = dialect.upsertBindOrder(columns, keys);
                    try (PreparedStatement write = tgt.prepareStatement(sql)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        int inBatch = 0;
                        while (rs.next()) {
                            JdbcRowMapper.readRowInto(rs, columns, jdbcTypes, typeNames,
                                    DatabaseType.ORACLE, row);
                            JdbcRowMapper.bind(write, row, bindOrder, typeByColumn, dialect);
                            write.addBatch();
                            rows++;
                            if (++inBatch >= batchSize) {
                                int[] counts = write.executeBatch();
                                batchCalls++;
                                for (int count : counts) {
                                    if (count == Statement.SUCCESS_NO_INFO) {
                                        noInfo++;
                                    } else if (count == 1) {
                                        ones++;
                                    } else if (count == 0) {
                                        zeros++;
                                    } else {
                                        other++;
                                    }
                                }
                                tgt.commit();
                                inBatch = 0;
                            }
                        }
                        if (inBatch > 0) {
                            int[] counts = write.executeBatch();
                            batchCalls++;
                            for (int count : counts) {
                                if (count == Statement.SUCCESS_NO_INFO) {
                                    noInfo++;
                                } else if (count == 1) {
                                    ones++;
                                } else if (count == 0) {
                                    zeros++;
                                } else {
                                    other++;
                                }
                            }
                        }
                    }
                }
            }
            tgt.commit();
        }
        long nanos = System.nanoTime() - start;
        return new Run(index, label, targetTable, batchSize, rows, nanos, batchCalls,
                ones, zeros, noInfo, other);
    }

    // --- 2) engine end to end: correctness and scaling -------------------------------------

    @Test
    void engineParallelFullLoadScalesAndStaysCorrect() throws SQLException {
        List<Run> runs = new ArrayList<>();
        for (int parallelism : new int[] {1, 2, 4}) {
            String target = "LOAD_TGT_P" + parallelism;
            recreate(target);
            ListAppender<ILoggingEvent> meterLogs = attachMeterLog();
            DataSyncService.TableSyncResult result;
            long start = System.nanoTime();
            try {
                result = syncWithEngine(target, parallelism);
            } finally {
                detachMeterLog(meterLogs);
            }
            long nanos = System.nanoTime() - start;

            assertThat(result.isSuccess()).as(result.getError()).isTrue();
            assertThat(result.getRowsWritten()).isEqualTo(ROWS);
            assertThat(count(target)).isEqualTo(ROWS);
            assertThat(sumId(target)).isEqualTo(checksum(SRC));
            assertThat(meterLogs.list)
                    .as("the throughput meter must have reported the pass")
                    .anyMatch(e -> e.getFormattedMessage().contains("rows/s="))
                    .anyMatch(e -> e.getFormattedMessage().contains("commit="));

            runs.add(new Run(runs.size() + 1, "engine parallel=" + parallelism, target,
                    FULL_LOAD_BATCH, ROWS, nanos, -1, 0, 0, 0, 0));
        }

        System.out.println();
        System.out.println("Engine end-to-end (chunk " + CHUNK_SIZE + ", batch/fetch "
                + FULL_LOAD_BATCH + "/" + FULL_LOAD_FETCH + ", 10 columns)");
        System.out.println(renderEngineTable(runs));
    }

    private DataSyncService.TableSyncResult syncWithEngine(String targetTable, int parallelism)
            throws SQLException {
        ChunkCheckpointRepository checkpoints = mock(ChunkCheckpointRepository.class);
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(CHUNK_SIZE);
        properties.setFullLoadParallelism(parallelism);

        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(new LinkedHashMap<>(Map.of(SRC, targetTable)));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(11L);
        sourceCfg.setType(DatabaseType.ORACLE);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(12L);
        targetCfg.setType(DatabaseType.ORACLE);

        Project project = new Project();
        project.setId(1L);
        project.setName("throughput-bench");

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
                .batchSize(500)
                .fullLoadBatchSize(FULL_LOAD_BATCH)
                .fullLoadFetchSize(FULL_LOAD_FETCH)
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

    // --- 3) engine incremental: the MERGE path every later cycle uses ----------------------

    /**
     * The incremental window is not an initial load, so it goes through the dialect's upsert
     * ({@code MERGE}) rather than INSERT-first. The target is seeded with different values so
     * every matched row has to be updated: a run that only re-read and skipped would look
     * fast and do nothing.
     */
    @Test
    void engineIncrementalUpsertThroughput() throws SQLException {
        String target = "INCR_TGT";
        recreate(target);
        seedIncrementalTarget(target);

        ListAppender<ILoggingEvent> meterLogs = attachMeterLog();
        DataSyncService.TableSyncResult result;
        long start = System.nanoTime();
        try {
            result = syncIncremental(target);
        } finally {
            detachMeterLog(meterLogs);
        }
        long nanos = System.nanoTime() - start;

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getRowsWritten()).isEqualTo(ROWS);
        assertThat(count(target)).isEqualTo(ROWS);
        assertThat(sumId(target)).isEqualTo(checksum(SRC));
        assertThat(queryLong("SELECT COUNT(*) FROM " + target + " WHERE NAME LIKE 'old-%'"))
                .as("every seeded row must have been updated")
                .isZero();
        assertThat(meterLogs.list)
                .as("the throughput meter must have reported the pass")
                .anyMatch(e -> e.getFormattedMessage().contains("rows/s="));

        System.out.println();
        System.out.println("Engine incremental upsert (window " + ROWS + " rows, batch "
                + INCREMENTAL_BATCH + ", 10 columns)");
        System.out.println(String.format("%-24s %10s %12s%n", "variant", "seconds", "rows/s")
                + String.format("%-24s %10s %12.0f%n", "engine incremental",
                        seconds(nanos), ROWS * 1_000_000_000.0 / nanos));
    }

    private DataSyncService.TableSyncResult syncIncremental(String targetTable)
            throws SQLException {
        ChunkCheckpointRepository checkpoints = mock(ChunkCheckpointRepository.class);
        SyncProperties properties = new SyncProperties();

        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(new LinkedHashMap<>(Map.of(SRC, targetTable)));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(11L);
        sourceCfg.setType(DatabaseType.ORACLE);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(12L);
        targetCfg.setType(DatabaseType.ORACLE);

        Project project = new Project();
        project.setId(1L);
        project.setName("throughput-bench");

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
                .batchSize(INCREMENTAL_BATCH)
                .connectionProvider(cfg -> open())
                .build();

        TableMeta meta;
        try (Connection conn = open()) {
            meta = new OracleMetadataReader().readTable(conn, schema, SRC);
        }
        CursorStrategy strategy = CursorStrategy.identity("ID", Types.NUMERIC,
                "numeric primary key");

        // initial-load-done plus a cursor at 0 routes syncTable to the incremental window,
        // whose upper bound is MAX(ID): every source row is in the window exactly once.
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("0");

        DataSyncService service = new DataSyncService(properties, checkpoints);
        try (Connection sourceConn = open(); Connection targetConn = open()) {
            return service.syncTable(sourceConn, targetConn, meta, strategy, progress, ctx);
        }
    }

    // --- fixtures ---------------------------------------------------------------------------

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }

    private static void createSource(Connection conn) throws SQLException {
        if (exists(conn, SRC)) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + SRC + DDL);
            // Direct-path load: the fixture must not take minutes to build, and this is the
            // fastest way to get half a million realistic rows into the source.
            st.execute("INSERT /*+ APPEND */ INTO " + SRC
                    + " SELECT LEVEL, 'name-' || LEVEL, LEVEL * 1.25, SYSDATE - MOD(LEVEL, 365),"
                    + " RPAD('memo-' || LEVEL, 120, 'x'), MOD(LEVEL, 1000),"
                    + " CASE MOD(LEVEL, 3) WHEN 0 THEN 'NEW' WHEN 1 THEN 'OPEN' ELSE 'DONE' END,"
                    + " CAST(SYSTIMESTAMP - NUMTODSINTERVAL(MOD(LEVEL, 60), 'MINUTE') AS TIMESTAMP),"
                    + " 'code-' || TO_CHAR(MOD(LEVEL, 9999), 'FM0000'), MOD(LEVEL, 100) / 7"
                    + " FROM dual CONNECT BY LEVEL <= " + ROWS);
            st.execute("COMMIT");
            st.execute("CREATE INDEX IX_LOAD_SRC_NAME ON " + SRC + " (NAME)");
            st.execute("CREATE INDEX IX_LOAD_SRC_QTY ON " + SRC + " (QTY)");
        }
    }

    /** Same shape as the source, different values: every row must be an actual update. */
    private static void seedIncrementalTarget(String table) throws SQLException {
        exec("INSERT /*+ APPEND */ INTO " + table
                + " SELECT LEVEL, 'old-' || LEVEL, 0, SYSDATE, RPAD('old', 120, 'x'),"
                + " 0, 'NEW', SYSTIMESTAMP, 'old', 0"
                + " FROM dual CONNECT BY LEVEL <= " + ROWS);
        exec("COMMIT");
    }

    private static void recreate(String table) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            dropQuietly(st, table);
            st.execute("CREATE TABLE " + table + DDL);
        }
    }

    private static boolean exists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM user_tables WHERE table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void dropQuietly(Statement st, String table) {
        try {
            st.execute("DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE");
        } catch (SQLException ignored) {
            // First run: nothing to drop.
        }
    }

    private static long count(String table) throws SQLException {
        return queryLong("SELECT COUNT(*) FROM " + table);
    }

    private static long sumId(String table) throws SQLException {
        return queryLong("SELECT SUM(ID) FROM " + table);
    }

    private static long checksum(String table) throws SQLException {
        return sumId(table);
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertCopyIsCorrect(Run run) throws SQLException {
        assertThat(run.rows()).isEqualTo(ROWS);
        assertThat(count(run.table())).as(run.label()).isEqualTo(ROWS);
        assertThat(sumId(run.table())).as(run.label()).isEqualTo(checksum(SRC));
    }

    private static ListAppender<ILoggingEvent> attachMeterLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        meterLogger().addAppender(appender);
        return appender;
    }

    private static void detachMeterLog(ListAppender<ILoggingEvent> appender) {
        meterLogger().detachAppender(appender);
        appender.stop();
    }

    private static Logger meterLogger() {
        // Named, not class-literal: ThroughputMeter is package-private to the sync package.
        return (Logger) LoggerFactory.getLogger("com.qqmu.jync.service.sync.ThroughputMeter");
    }

    // --- reporting --------------------------------------------------------------------------

    private static void printReport(long scanNanos, List<Run> runs, long indexAfterNanos) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append("Raw JDBC control (").append(ROWS)
                .append(" rows, 10 columns, source scan ").append(seconds(scanNanos))
                .append("s)\n");
        sb.append(String.format("%-42s %6s %10s %12s %8s %8s %7s %7s %7s%n",
                "variant", "batch", "seconds", "rows/s", "batches", "cnt=1",
                "cnt=0", "noInfo", "other"));
        for (Run run : runs) {
            sb.append(String.format("%-42s %6d %10s %12.0f %8d %8d %7d %7d %7d%n",
                    run.label(), run.batchSize(), seconds(run.nanos()), run.rowsPerSecond(),
                    run.batchCalls(), run.countsOne(), run.countsZero(), run.countsNoInfo(),
                    run.countsOther()));
        }
        sb.append(String.format("%-42s %6s %10s %12s%n", "CREATE 2 secondary indexes afterwards",
                "-", seconds(indexAfterNanos), "-"));
        System.out.println(sb);
    }

    private static String renderEngineTable(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-24s %10s %12s%n", "variant", "seconds", "rows/s"));
        for (Run run : runs) {
            sb.append(String.format("%-24s %10s %12.0f%n", run.label(),
                    seconds(run.nanos()), run.rowsPerSecond()));
        }
        return sb.toString();
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000_000.0);
    }

    /** One measured copy. Update-count shapes are printed, not asserted: they are evidence. */
    private record Run(int index, String label, String table, int batchSize, int rows,
                       long nanos, int batchCalls, int countsOne, int countsZero,
                       int countsNoInfo, int countsOther) {

        double rowsPerSecond() {
            return nanos <= 0 ? 0 : rows * 1_000_000_000.0 / nanos;
        }
    }
}
