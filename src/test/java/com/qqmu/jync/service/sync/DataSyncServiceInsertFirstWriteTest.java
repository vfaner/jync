package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The write side of the INSERT-first shortcut: with an empty target of a supporting dialect the
 * initial load must actually issue plain {@code INSERT}s, not the dialect's upsert. The probe
 * deciding this is covered by {@link InitialLoadInsertFirstProbeTest}; this pins the statement
 * the row writer really prepares against a live target.
 */
class DataSyncServiceInsertFirstWriteTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private final List<String> prepared = new CopyOnWriteArrayList<>();
    private final List<String> probed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:ins_src;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ORDERS VALUES (1,'a'), (2,'b'), (3,'c')");
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        source.close();
    }

    @Test
    void anEmptyMySqlTargetIsWrittenWithPlainInsert() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:ins_tgt_my;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        exec(target, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        DataSyncService.TableSyncResult result = load(new MySqlDialect(), DatabaseType.MYSQL);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(countOrders()).isEqualTo(3);
        assertThat(probed).contains("SELECT 1 FROM `ORDERS` LIMIT 1");
        assertThat(prepared).anyMatch(sql -> sql.startsWith("INSERT INTO `ORDERS` ("));
        assertThat(prepared).noneMatch(sql -> sql.contains("ON DUPLICATE KEY"));
    }

    @Test
    void aNonEmptyMySqlTargetKeepsTheUpsert() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:ins_tgt_my_busy;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        exec(target, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "INSERT INTO ORDERS VALUES (99,'existing')");

        DataSyncService.TableSyncResult result = load(new MySqlDialect(), DatabaseType.MYSQL);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(countOrders()).isEqualTo(4);
        assertThat(prepared).anyMatch(sql -> sql.contains("ON DUPLICATE KEY UPDATE"));
    }

    @Test
    void anEmptyPostgresTargetIsWrittenWithPlainInsert() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:ins_tgt_pg;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        exec(target, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        DataSyncService.TableSyncResult result =
                load(new PostgresDialect(), DatabaseType.POSTGRESQL);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(countOrders()).isEqualTo(3);
        assertThat(probed).contains("SELECT 1 FROM \"ORDERS\" LIMIT 1");
        assertThat(prepared).anyMatch(sql -> sql.startsWith("INSERT INTO \"ORDERS\" ("));
        assertThat(prepared).noneMatch(sql -> sql.contains("ON CONFLICT"));
    }

    private DataSyncService.TableSyncResult load(SqlDialect targetDialect,
                                                 DatabaseType targetType) throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(2);
        properties.setFullLoadParallelism(1);
        DataSyncService service =
                new DataSyncService(properties, mock(ChunkCheckpointRepository.class));

        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(1L);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(2L);
        Project project = new Project();
        project.setId(3L);
        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(targetDialect)
                .targetType(targetType)
                .batchSize(50)
                .build();

        Connection recording = record(target);
        return service.syncTable(source, recording, ordersMeta(), IDENTITY,
                new SyncProgress(), ctx);
    }

    /** Delegates to the real target, recording the probe and every prepared statement. */
    private Connection record(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                DataSyncServiceInsertFirstWriteTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String) {
                        prepared.add((String) args[0]);
                    }
                    Object result = invoke(real, method, args);
                    // Prepared statements need no wrapping: their SQL was recorded above.
                    if (result instanceof java.sql.PreparedStatement) {
                        return result;
                    }
                    if (result instanceof Statement statement) {
                        return recordStatement(statement);
                    }
                    return result;
                });
    }

    private Statement recordStatement(Statement real) {
        return (Statement) Proxy.newProxyInstance(
                DataSyncServiceInsertFirstWriteTest.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String) {
                        probed.add((String) args[0]);
                    }
                    return invoke(real, method, args);
                });
    }

    private long countOrders() throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ORDERS")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static TableMeta ordersMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name));
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
