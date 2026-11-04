package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.Db2Dialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * End-to-end shape of the DB2 write path: the MERGE built from real source metadata must wrap
 * each {@code USING (VALUES ...)} parameter marker in an explicit CAST, or DB2 fails the load
 * with SQL0418 / SQLSTATE 42610 ("&lt;column&gt; already resolved to different type"). The
 * MERGE cannot run on H2, so the statement is captured at prepare time and its execution is
 * simulated; everything else (source read, target metadata probe) uses real H2.
 */
class DataSyncServiceDb2MergeTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private final List<String> prepared = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:db2_src;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE JYNC_SYNC_TEST (ID BIGINT PRIMARY KEY, FCS VARCHAR(36))");
        exec(source, "INSERT INTO JYNC_SYNC_TEST VALUES (1,'a'), (2,'b')");
        target = DriverManager.getConnection("jdbc:h2:mem:db2_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        // Pre-populated so the initial-load INSERT-first probe does not choose plain INSERTs.
        exec(target, "CREATE TABLE JYNC_SYNC_TEST (ID BIGINT PRIMARY KEY, FCS VARCHAR(36))");
        exec(target, "INSERT INTO JYNC_SYNC_TEST VALUES (9,'existing')");
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        source.close();
        exec(target, "DROP ALL OBJECTS");
        target.close();
    }

    @Test
    void theMergePreparedOnTheWritePathCarriesTypedMarkers() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(50);
        properties.setFullLoadParallelism(1);
        DataSyncService service =
                new DataSyncService(properties, mock(ChunkCheckpointRepository.class));

        Project project = new Project();
        project.setId(3L);
        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(new DatabaseConfig())
                .targetConfig(new DatabaseConfig())
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.DB2)
                .sourceDialect(new com.qqmu.jync.service.converter.GenericSqlDialect())
                .targetDialect(new Db2Dialect())
                .batchSize(50)
                .build();

        DataSyncService.TableSyncResult result = service.syncTable(source, intercept(target),
                tableMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        String merge = prepared.stream().filter(sql -> sql.startsWith("MERGE INTO")).findFirst()
                .orElseThrow(() -> new AssertionError("no MERGE was prepared: " + prepared));
        assertThat(merge).contains(
                "USING (VALUES (CAST(? AS BIGINT), CAST(? AS VARCHAR(36)))) AS s");
    }

    private Connection intercept(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                DataSyncServiceDb2MergeTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String sql
                            && sql.startsWith("MERGE INTO")) {
                        prepared.add(sql);
                        return fakeMerge();
                    }
                    return invoke(real, method, args);
                });
    }

    /** Accepts binds and reports every batched row merged, without sending DB2 SQL to H2. */
    private static PreparedStatement fakeMerge() {
        int[] batched = {0};
        return (PreparedStatement) Proxy.newProxyInstance(
                DataSyncServiceDb2MergeTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("addBatch".equals(name)) {
                        batched[0]++;
                        return null;
                    }
                    if ("executeBatch".equals(name)) {
                        int[] counts = new int[batched[0]];
                        java.util.Arrays.fill(counts, Statement.SUCCESS_NO_INFO);
                        batched[0] = 0;
                        return counts;
                    }
                    if ("executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) {
                        return "executeLargeUpdate".equals(name) ? 2L : 2;
                    }
                    if ("execute".equals(name)) {
                        return true;
                    }
                    if ("close".equals(name) || "clearBatch".equals(name)
                            || "clearParameters".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static TableMeta tableMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta fcs = new ColumnMeta();
        fcs.setName("FCS");
        fcs.setJdbcType(Types.VARCHAR);
        fcs.setSize(36);
        TableMeta table = new TableMeta();
        table.setName("JYNC_SYNC_TEST");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, fcs));
        return table;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static Object invoke(Object real, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
