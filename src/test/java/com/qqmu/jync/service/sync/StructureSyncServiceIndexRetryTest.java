package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;

/**
 * P2: a secondary index that fails while a table is created must be retried in later cycles and
 * removed from the retry queue once it lands, instead of being silently given up forever.
 */
class StructureSyncServiceIndexRetryTest {

    private Connection rawTarget;
    private Connection flakyTarget;
    private StructureSyncService structure;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        rawTarget = DriverManager.getConnection(
                "jdbc:h2:mem:idx_retry;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = rawTarget.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
            st.execute("CREATE TABLE T (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        }
        flakyTarget = failIndexOnce(rawTarget);

        structure = new StructureSyncService(
                new com.qqmu.jync.service.converter.SqlBodyConverter());

        Project project = new Project();
        project.setId(7L);
        project.setName("P");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        rawTarget.close();
    }

    @Test
    void failedIndexIsQueuedThenRecoveredOnTheNextCycle() throws SQLException {
        TableMeta table = tableWithIndex("IDX_NAME");

        // First cycle: table already exists, the index fails with a transient error.
        ChangeEvent create = ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                "T", "table create", table);
        StructureSyncService.ApplyOutcome first = structure.apply(flakyTarget, create, ctx);

        assertThat(first.applied).isTrue(); // index failure must not fail the table outcome
        assertThat(indexExists("IDX_NAME")).isFalse();
        assertThat(structure.retryPendingIndexes(flakyTarget, ctx)).isEmpty(); // still failing

        // Second cycle: the flakiness is spent, the retry creates the index.
        List<String> recovered = structure.retryPendingIndexes(flakyTarget, ctx);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0)).contains("IDX_NAME");
        assertThat(indexExists("IDX_NAME")).isTrue();

        // Queue drains: later cycles do nothing.
        assertThat(structure.retryPendingIndexes(flakyTarget, ctx)).isEmpty();
    }

    @Test
    void evictProjectDropsTheRetryQueue() throws SQLException {
        TableMeta table = tableWithIndex("IDX_OTHER");
        ChangeEvent create = ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                "T", "table create", table);
        structure.apply(flakyTarget, create, ctx);

        structure.evictProject(99L); // different project: queue stays
        assertThat(structure.retryPendingIndexes(flakyTarget, ctx)).isEmpty();
        assertThat(indexExists("IDX_OTHER")).isFalse();

        structure.evictProject(7L); // own project: queue gone, retry is a no-op
        // With the queue drained even against a now-healthy target nothing is created.
        assertThat(structure.retryPendingIndexes(flakyTarget, ctx)).isEmpty();
    }

    private TableMeta tableWithIndex(String indexName) {
        IndexMeta index = new IndexMeta();
        index.setName(indexName);
        index.setColumns(List.of("NAME"));
        TableMeta table = new TableMeta();
        table.setName("T");
        table.setIndexes(List.of(index));
        return table;
    }

    private boolean indexExists(String indexName) throws SQLException {
        try (Statement st = rawTarget.createStatement();
             var rs = st.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = '"
                             + indexName + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /** Connection whose first two CREATE INDEX attempts fail; the third goes through. */
    private static Connection failIndexOnce(Connection delegate) {
        int[] failures = {0};
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return flakyStatement(delegate.createStatement(), failures);
                    }
                    return invokeDelegate(method, delegate, args);
                });
    }

    private static Statement flakyStatement(Statement delegate, int[] failures) {
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())
                            && args[0] instanceof String
                            && ((String) args[0]).toUpperCase().contains("INDEX")
                            && failures[0] < 2) {
                        failures[0]++;
                        throw new SQLException("Lock request time out period exceeded", "40001");
                    }
                    return invokeDelegate(method, delegate, args);
                });
    }

    /** Reflection invoke that surfaces the real target exception instead of wrapping it. */
    private static Object invokeDelegate(java.lang.reflect.Method method, Object delegate,
                                         Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
