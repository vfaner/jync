package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * An unchecked failure inside the write loop must roll back the open transaction.
 *
 * <p>{@code copyRows} disables auto-commit for the whole pass and already rolls back for
 * {@code SQLException} and a lost lease. Every other exception — a mapping or conversion bug, a
 * driver that throws unchecked out of {@code commit}/{@code executeBatch} — used to skip both
 * catches and land straight in {@code finally}, where restoring auto-commit makes the JDBC
 * driver <em>commit</em> whatever the failing batch had already applied. The engine now isolates
 * such exceptions per table, so without a rollback here the partial batch is quietly kept.
 */
class DataSyncServiceUncheckedFailureRollbackTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:unchecked_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:unchecked_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        service = new DataSyncService(new SyncProperties());
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(2)
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
    void anUncheckedCommitFailureRollsBackTheAppliedBatch() throws SQLException {
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");

        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));

        AtomicInteger rollbacks = new AtomicInteger();
        Connection failingCommit = proxyThatFailsCommitOn(target, rollbacks);

        assertThatThrownBy(() -> service.syncTable(source, failingCommit, table, IDENTITY,
                new SyncProgress(), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("driver blew up mid-batch");

        assertThat(rollbacks).as("the open transaction must be rolled back").hasValue(1);
        // Before the fix, restoring auto-commit in the finally block committed the two rows
        // the failing batch had applied, leaving the target at 2 rows from a failed load.
        assertThat(count("ITEMS")).as("a failed batch must leave no rows behind").isZero();
    }

    /**
     * Delegating connection whose first {@code commit()} throws an unchecked exception, the
     * shape of a driver-side failure the JDBC contract does not advertise. Everything else —
     * including {@code rollback()} — reaches the real connection.
     */
    private static Connection proxyThatFailsCommitOn(Connection delegate,
                                                     AtomicInteger rollbacks) {
        AtomicInteger commits = new AtomicInteger();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("commit".equals(method.getName()) && commits.getAndIncrement() == 0) {
                        throw new IllegalStateException("driver blew up mid-batch");
                    }
                    if ("rollback".equals(method.getName())) {
                        rollbacks.incrementAndGet();
                    }
                    return method.invoke(delegate, args);
                });
    }

    private long count(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
