package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The emulated-upsert path (update-by-key, insert when the key is absent) retries the UPDATE
 * when the INSERT hits a unique conflict — that is how a concurrent writer's row converges.
 *
 * <p>SQLState 23 is the whole integrity-constraint class, not just unique conflicts: NOT NULL
 * (23502), foreign key (23503) and CHECK (23514) failures all start with 23. Treating those as
 * "row already present" retried the UPDATE, saw zero rows, and dropped the row with no error
 * and no statistic — silent data loss. They must surface as a failure instead.
 */
class DataSyncServiceIntegrityViolationTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection(
                "jdbc:h2:mem:integrity_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection(
                "jdbc:h2:mem:integrity_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50), AMOUNT INT)");
        // The target is stricter than the source: a row the source holds legally cannot be
        // stored here. The write must fail loudly rather than disappear.
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50) NOT NULL,"
                + " AMOUNT INT CHECK (AMOUNT >= 0))");
        exec(source, "INSERT INTO ITEMS VALUES (1, NULL, 10)");

        Project project = new Project();
        project.setId(11L);
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void aNotNullViolationFailsTheTableInsteadOfVanishingAsAUniqueConflict() throws SQLException {
        DataSyncService.TableSyncResult result = service().syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess())
                .as("a NOT NULL violation must not be read as 'row already present'")
                .isFalse();
        assertThat(result.getError()).containsIgnoringCase("null");
        assertThat(count()).isZero();
    }

    @Test
    void aCheckViolationFailsTheTableInsteadOfVanishingAsAUniqueConflict() throws SQLException {
        exec(source, "DELETE FROM ITEMS");
        exec(source, "INSERT INTO ITEMS VALUES (2, 'ok', -5)");

        DataSyncService.TableSyncResult result = service().syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess())
                .as("a CHECK violation must not be read as 'row already present'")
                .isFalse();
        assertThat(count()).isZero();
    }

    @Test
    void aUniqueConflictOnAnotherUniqueKeyWithoutThePkRowDoesNotVanish() throws SQLException {
        // 目标端多出一条源端没有的唯一约束,且占用该值的行主键不同:INSERT 撞重复后
        // 回退 UPDATE 命中 0 行,原实现把这一行既不写入也不计数地丢掉,整表仍报成功。
        exec(target, "DROP TABLE ITEMS");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50), AMOUNT INT,"
                + " CONSTRAINT UQ_ITEMS_NAME UNIQUE (NAME))");
        exec(target, "INSERT INTO ITEMS VALUES (7, 'dup', 1)");
        exec(source, "DELETE FROM ITEMS");
        exec(source, "INSERT INTO ITEMS VALUES (5, 'dup', 2)");

        DataSyncService.TableSyncResult result = service().syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess())
                .as("the conflicting row cannot be written and must not disappear silently")
                .isFalse();
    }

    @Test
    void aForeignKeyStyle23000OnAKeyOnlyTargetTableSurfacesWhenTheRowIsAbsent() throws SQLException {
        // 全主键表:update 为 null,原实现把任何 23000 都当成"行已存在"的重投。
        // MySQL/Oracle/SQL Server 的外键/CHECK 违约同样是 23000,于是引用尚未同步
        // 父行的记录会无声消失——这里用 23000 的外键报文模拟该场景。
        exec(source, "CREATE TABLE LINKS (A BIGINT, B BIGINT, PRIMARY KEY (A, B))");
        exec(target, "CREATE TABLE LINKS (A BIGINT, B BIGINT, PRIMARY KEY (A, B))");
        exec(source, "INSERT INTO LINKS VALUES (1, 2)");

        Connection flakyTarget = insertsFailWith23000(target);
        DataSyncService.TableSyncResult result = service().syncTable(
                source, flakyTarget, linksMeta(),
                CursorStrategy.identity("A", Types.BIGINT, "numeric primary key"),
                new SyncProgress(), ctx);

        assertThat(result.isSuccess())
                .as("a 23000 that is not a duplicate of the same key must surface")
                .isFalse();
        assertThat(result.getError()).containsIgnoringCase("foreign key");
    }

    /** Target stand-in whose INSERTs raise the MySQL FK violation (SQLState 23000, code 1452). */
    private static Connection insertsFailWith23000(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                DataSyncServiceIntegrityViolationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0
                            && String.valueOf(args[0]).trim().toUpperCase(Locale.ROOT)
                                    .startsWith("INSERT")) {
                        return fkFailingStatement((PreparedStatement) result);
                    }
                    return result;
                });
    }

    private static PreparedStatement fkFailingStatement(PreparedStatement delegate) {
        return (PreparedStatement) Proxy.newProxyInstance(
                DataSyncServiceIntegrityViolationTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())
                            && (args == null || args.length == 0)) {
                        throw new SQLException("Cannot add or update a child row:"
                                + " a foreign key constraint fails", "23000", 1452);
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private TableMeta linksMeta() {
        ColumnMeta a = new ColumnMeta();
        a.setName("A");
        a.setJdbcType(Types.BIGINT);
        ColumnMeta b = new ColumnMeta();
        b.setName("B");
        b.setJdbcType(Types.BIGINT);
        TableMeta table = new TableMeta();
        table.setName("LINKS");
        table.setPrimaryKeys(List.of("A", "B"));
        table.setColumns(List.of(a, b));
        return table;
    }

    private long count() throws SQLException {
        try (Statement st = target.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private DataSyncService service() {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(0);
        return new DataSyncService(properties, mock(ChunkCheckpointRepository.class));
    }

    private TableMeta itemsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        ColumnMeta amount = new ColumnMeta();
        amount.setName("AMOUNT");
        amount.setJdbcType(Types.INTEGER);
        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name, amount));
        return table;
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
