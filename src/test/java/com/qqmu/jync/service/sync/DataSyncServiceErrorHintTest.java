package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

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
 * The change log shows the failure verbatim, so the failure has to say what to do about it.
 *
 * <p>"Statement cancelled due to timeout or client request" and "Connection is closed" are
 * driver and pool internals: an operator reading them cannot tell whether to raise a limit,
 * add an index, or simply wait for the next cycle, which is exactly what happened with the
 * reported real-time project.
 */
class DataSyncServiceErrorHintTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;
    private TableMeta table;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:hint_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:hint_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1, 'a')");

        service = new DataSyncService(new SyncProperties());
        table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
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
    void aStatementTimeoutSaysHowToGetMoreTime() {
        Connection dead = failingOn("SELECT * FROM",
                new SQLTimeoutException("Statement cancelled due to timeout or client request"));

        DataSyncService.TableSyncResult result =
                service.syncTable(dead, target, table, IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError())
                .as("the driver's own message must survive; the hint is appended to it")
                .startsWith("Statement cancelled due to timeout or client request")
                .contains("copy-timeout-seconds")
                .contains("index");
    }

    @Test
    void aRetiredPooledConnectionSaysTheTableIsRetried() {
        Connection dead = failingOn("SELECT * FROM", new SQLException("Connection is closed"));

        DataSyncService.TableSyncResult result =
                service.syncTable(dead, target, table, IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError())
                .startsWith("Connection is closed")
                .contains("retried");
    }

    @Test
    void anOrdinaryFailureKeepsItsOwnMessage() {
        Connection broken = failingOn("SELECT * FROM", new SQLException("Column ID not found"));

        DataSyncService.TableSyncResult result =
                service.syncTable(broken, target, table, IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("Column ID not found");
    }

    /** Delegating source connection whose matching statements fail with {@code failure}. */
    private static Connection failingOn(String sqlPrefix, SQLException failure) {
        try {
            Connection delegate = DriverManager.getConnection(
                    "jdbc:h2:mem:hint_src;DB_CLOSE_DELAY=-1", "sa", "");
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName()) && args != null
                                && args.length > 0 && args[0] instanceof String
                                && ((String) args[0]).startsWith(sqlPrefix)) {
                            return failingStatement((PreparedStatement) invoke(delegate, method, args),
                                    failure);
                        }
                        return invoke(delegate, method, args);
                    });
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedStatement failingStatement(PreparedStatement delegate,
                                                      SQLException failure) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        throw failure;
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

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
