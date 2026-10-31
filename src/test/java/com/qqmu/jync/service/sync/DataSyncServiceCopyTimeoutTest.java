package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * A row copy legitimately runs for hours; the five-minute data-plane bound does not.
 *
 * <p>{@code sync.query-timeout-seconds} exists to stop a wedged statement from parking the
 * worker, and it is right for the short statements around the copy (the target upsert, the
 * audit's {@code COUNT(*)}). The two reads whose duration scales with the size of the table —
 * the high-watermark {@code MAX(cursor)} and the window {@code SELECT *} — cannot live under
 * that bound: on MySQL Connector/J the expiry cancels the statement and HikariCP then evicts
 * the connection, so one table larger than the budget turns into "Statement cancelled due to
 * timeout" plus a burst of "Connection is closed" rows for every table after it.
 */
class DataSyncServiceCopyTimeoutTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:copy_timeout_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:copy_timeout_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1, 'a'), (2, 'b')");

        ctx = SyncContext.builder()
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
    void theWatermarkReadAndTheWindowReadUseTheCopyTimeout() throws SQLException {
        SyncProperties props = new SyncProperties();
        props.setQueryTimeoutSeconds(300);
        props.setCopyTimeoutSeconds(1800);
        DataSyncService service = new DataSyncService(props);

        List<SqlTimeout> recorded = new CopyOnWriteArrayList<>();
        TableMeta table = table("ITEMS");
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("0");

        service.syncTable(recording(source, recorded), recording(target, recorded),
                table, IDENTITY, progress, ctx);

        assertThat(timeoutOf(recorded, "SELECT MAX("))
                .as("the high-watermark MAX scales with the table, so it takes the copy budget")
                .isEqualTo(1800);
        assertThat(timeoutOf(recorded, "SELECT * FROM"))
                .as("the window SELECT is the copy itself and takes the copy budget")
                .isEqualTo(1800);
        assertThat(recorded)
                .as("the short statements around the copy keep sync.query-timeout-seconds")
                .anySatisfy(entry -> assertThat(entry.timeoutSeconds).isEqualTo(300));
    }

    private static Integer timeoutOf(List<SqlTimeout> recorded, String prefix) {
        return recorded.stream()
                .filter(entry -> entry.sql.startsWith(prefix))
                .map(entry -> entry.timeoutSeconds)
                .findFirst()
                .orElse(null);
    }

    private static TableMeta table(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setPrimaryKeys(List.of("ID"));
        return table;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** One statement and the timeout it was given, recorded as SQL executes. */
    private record SqlTimeout(String sql, int timeoutSeconds) {
    }

    /**
     * Delegating connection that reports every statement's SQL and the timeout it had when it
     * ran. The timeout is set before the SQL is known on a plain {@link Statement}, so it is
     * captured at execute time rather than at set time.
     */
    private static Connection recording(Connection delegate, List<SqlTimeout> recorded) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String) {
                        return recordingStatement((PreparedStatement) result,
                                PreparedStatement.class, (String) args[0], recorded);
                    }
                    if ("createStatement".equals(method.getName())) {
                        return recordingStatement((Statement) result, Statement.class, null,
                                recorded);
                    }
                    return result;
                });
    }

    private static Object recordingStatement(Object delegate, Class<?> type, String preparedSql,
                                             List<SqlTimeout> recorded) {
        int[] timeout = {-1};
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if ("setQueryTimeout".equals(method.getName())) {
                        timeout[0] = (Integer) args[0];
                    }
                    String executedSql = preparedSql;
                    if (executedSql == null && args != null && args.length == 1
                            && args[0] instanceof String) {
                        executedSql = (String) args[0];
                    }
                    boolean executes = "executeQuery".equals(method.getName())
                            || "executeUpdate".equals(method.getName())
                            || "execute".equals(method.getName());
                    if (executes && executedSql != null) {
                        recorded.add(new SqlTimeout(executedSql, timeout[0]));
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
