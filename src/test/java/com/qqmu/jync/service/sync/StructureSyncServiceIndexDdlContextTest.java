package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlBodyConverter;

/**
 * Index DDL needs the owning table: on MySQL a column the table had narrowed to LONGTEXT
 * cannot be indexed without a key length, and only the table's shape says which columns
 * those are. Every path that creates an index — the table CREATE, a standalone index
 * event, and the retry queue — has to hand the table over.
 */
class StructureSyncServiceIndexDdlContextTest {

    private Connection rawTarget;
    private StructureSyncService structure;
    private SyncContext ctx;
    private RecordingDialect dialect;

    @BeforeEach
    void setUp() throws SQLException {
        rawTarget = DriverManager.getConnection(
                "jdbc:h2:mem:index_ddl_ctx;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = rawTarget.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
            st.execute("CREATE TABLE T (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        }
        structure = new StructureSyncService(new SqlBodyConverter());
        dialect = new RecordingDialect();

        Project project = new Project();
        project.setId(9L);
        project.setName("P");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(dialect)
                .sourceType(DatabaseType.ORACLE)
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        rawTarget.close();
    }

    @Test
    void creatingATablePassesItToIndexDdl() throws SQLException {
        TableMeta table = tableWithIndex("IDX_NAME");

        structure.apply(rawTarget, ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                "T", "table create", table), ctx);

        assertThat(dialect.seen).containsExactly(table);
    }

    @Test
    void aStandaloneIndexEventPassesItsTableToIndexDdl() throws SQLException {
        TableMeta table = tableWithIndex("IDX_NAME");
        structure.apply(rawTarget, ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                "T", "table create", table), ctx);
        dialect.seen.clear();

        IndexMeta index = table.getIndexes().get(0);
        ChangeEvent event = ChangeEvent.of(ObjectType.INDEX, ChangeType.CREATE,
                "T", "new index", index);
        event.setSourceTable(table);
        structure.apply(rawTarget, event, ctx);

        assertThat(dialect.seen).containsExactly(table);
    }

    @Test
    void addingAMissingColumnPassesTheTableToColumnDdl() throws SQLException {
        TableMeta table = tableWithIndex("IDX_NAME");
        ColumnMeta note = new ColumnMeta();
        note.setName("NOTE");
        note.setTypeName("VARCHAR");
        note.setJdbcType(Types.VARCHAR);
        note.setSize(100);
        table.getColumns().add(note);

        structure.addMissingColumns(rawTarget, ctx, "T", table);

        assertThat(dialect.seenColumns).containsExactly(table);
    }

    @Test
    void aColumnAlterPassesItsTableToColumnDdl() throws SQLException {
        TableMeta table = tableWithIndex("IDX_NAME");
        ColumnMeta name = table.getColumns().get(0);
        name.setRemarks("new comment");

        ChangeEvent event = ChangeEvent.of(ObjectType.COLUMN, ChangeType.ALTER,
                "T", "column comment changed", name);
        event.setSourceTable(table);
        structure.apply(rawTarget, event, ctx);

        assertThat(dialect.seenColumns).containsExactly(table);
    }

    @Test
    void anIndexTheTargetCannotBuildIsSkippedWithoutFailingTheTable() throws SQLException {
        TableMeta table = tableWithIndex("IDX_SKIP");
        RecordingDialect skipping = new RecordingDialect() {
            @Override
            public String getCreateIndexSql(IndexMeta index, String schema, String tableName,
                                            TableMeta sourceTable, DatabaseType sourceType) {
                return null; // e.g. a UNIQUE index on a column MySQL had to narrow
            }
        };
        SyncContext skipCtx = SyncContext.builder()
                .project(ctx.getProject())
                .config(ctx.getConfig())
                .targetDialect(skipping)
                .sourceType(DatabaseType.ORACLE)
                .batchSize(50)
                .build();
        StructureSyncService service = new StructureSyncService(new SqlBodyConverter());

        StructureSyncService.ApplyOutcome outcome = service.apply(rawTarget,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "table create", table),
                skipCtx);

        assertThat(outcome.applied).isTrue();
        // Nothing was attempted, so nothing may be queued for a retry that cannot succeed.
        assertThat(service.retryPendingIndexes(rawTarget, skipCtx)).isEmpty();
    }

    @Test
    void aQueuedIndexIsRetriedWithItsTable() throws SQLException {
        TableMeta table = tableWithIndex("IDX_NAME");
        structure.apply(flakyTarget(rawTarget), ChangeEvent.of(ObjectType.TABLE,
                ChangeType.CREATE, "T", "table create", table), ctx);
        assertThat(dialect.seen).containsExactly(table); // first attempt, index failed

        // The retry queue has no other source for the table; it must have kept it.
        structure.retryPendingIndexes(rawTarget, ctx);

        assertThat(dialect.seen).containsExactly(table, table);
    }

    private TableMeta tableWithIndex(String indexName) {
        IndexMeta index = new IndexMeta();
        index.setName(indexName);
        index.setColumns(List.of("NAME"));
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setTypeName("VARCHAR");
        name.setJdbcType(Types.VARCHAR);
        name.setSize(50);
        TableMeta table = new TableMeta();
        table.setName("T");
        table.getColumns().add(name);
        table.setIndexes(List.of(index));
        return table;
    }

    /** Connection whose first CREATE INDEX attempt fails, like a transient lock timeout. */
    private static Connection flakyTarget(Connection delegate) {
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
                            && failures[0] < 1) {
                        failures[0]++;
                        throw new SQLException("Lock request time out period exceeded", "40001");
                    }
                    return invokeDelegate(method, delegate, args);
                });
    }

    private static Object invokeDelegate(java.lang.reflect.Method method, Object delegate,
                                         Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Records which table each index/column DDL was built from. */
    private static class RecordingDialect extends GenericSqlDialect {
        private final List<TableMeta> seen = new ArrayList<>();
        private final List<TableMeta> seenColumns = new ArrayList<>();

        @Override
        public String getCreateIndexSql(IndexMeta index, String schema, String table,
                                        TableMeta sourceTable, DatabaseType sourceType) {
            seen.add(sourceTable);
            return super.getCreateIndexSql(index, schema, table);
        }

        @Override
        public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                      DatabaseType sourceType, TableMeta sourceTable) {
            seenColumns.add(sourceTable);
            return super.getAddColumnSql(schema, table, column, sourceType);
        }

        @Override
        public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                         DatabaseType sourceType, TableMeta sourceTable) {
            seenColumns.add(sourceTable);
            return super.getModifyColumnSql(schema, table, column, sourceType);
        }
    }
}
