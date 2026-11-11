package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;

/**
 * Which statement an upsert run prepares against a MySQL-family target.
 *
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE} only converges when the target enforces
 * uniqueness on exactly the configured key: without such an index every run inserts another
 * copy of a row whose key it cannot match. The single statement is therefore used only when
 * Jync created the table (and put the primary key on it) or the target already carries a
 * matching unique index; everything else falls back to the update-then-insert emulation.
 */
class EtlRunnerUpsertTargetTest {

    @Test
    void aPreExistingTargetWithoutAUniqueIndexUsesTheEmulatedUpsert() throws SQLException {
        Connection target = existingTarget(new IndexRow("IDX_NAME", "NAME", false));

        EtlRunResult result = runner().run(upsertTask(), source(), mysqlTarget(), provider(target));

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        // The key exists only in the configured columns, not in the target's dictionary:
        // ON DUPLICATE KEY UPDATE would insert a second row instead of updating the first.
        verify(target).prepareStatement(startsWith("UPDATE dst SET name = ?"));
        verify(target, never()).prepareStatement(contains("ON DUPLICATE KEY"));
        // MySQL keeps the database in the JDBC catalog, so that is the scope it is read in.
        verify(target.getMetaData())
                .getIndexInfo(eq("APP"), isNull(), eq("dst"), eq(true), eq(true));
    }

    @Test
    void aTargetWhosePrimaryKeyCoversTheConfiguredKeyKeepsTheSingleStatementUpsert()
            throws SQLException {
        Connection target = existingTarget(new IndexRow("PRIMARY", "ID", true));

        EtlRunResult result = runner().run(upsertTask(), source(), mysqlTarget(), provider(target));

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        verify(target).prepareStatement(startsWith("INSERT INTO dst (id, name) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE name = VALUES(name)"));
        verify(target, never()).prepareStatement(startsWith("UPDATE "));
    }

    @Test
    void unreadableIndexMetadataFallsBackToTheEmulatedUpsert() throws SQLException {
        Connection target = mock(Connection.class);
        stubTarget(target, true);
        when(target.getMetaData()).thenThrow(new SQLException("dictionary unavailable"));

        EtlRunResult result = runner().run(upsertTask(), source(), mysqlTarget(), provider(target));

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        verify(target).prepareStatement(startsWith("UPDATE dst SET name = ?"));
        verify(target, never()).prepareStatement(contains("ON DUPLICATE KEY"));
    }

    @Test
    void aTableJyncCreatesCarriesItsPrimaryKeySoNoMetadataIsRead() throws SQLException {
        Connection target = mock(Connection.class);
        stubTarget(target, false);

        EtlRunResult result = runner().run(upsertTask(), source(), mysqlTarget(), provider(target));

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        verify(target, never()).getMetaData();
        verify(target).prepareStatement(startsWith("INSERT INTO dst (id, name) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE name = VALUES(name)"));
    }

    // --- harness ------------------------------------------------------------------------

    /** A target that already holds the table; its dictionary reports {@code rows}. */
    private static Connection existingTarget(IndexRow... rows) throws SQLException {
        Connection connection = mock(Connection.class);
        stubTarget(connection, true);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("APP");
        ResultSet indexRows = mock(ResultSet.class);
        when(metadata.getIndexInfo(any(), any(), anyString(), eq(true), eq(true)))
                .thenReturn(indexRows);
        int[] cursor = {-1};
        when(indexRows.next()).thenAnswer(invocation -> ++cursor[0] < rows.length);
        when(indexRows.getShort("TYPE")).thenReturn(DatabaseMetaData.tableIndexOther);
        when(indexRows.getString(anyString())).thenAnswer(invocation -> {
            IndexRow row = rows[cursor[0]];
            String field = invocation.getArgument(0);
            return switch (field) {
                case "INDEX_NAME" -> row.index();
                case "COLUMN_NAME" -> row.column();
                case "TABLE_NAME" -> "dst";
                default -> null;
            };
        });
        when(indexRows.getBoolean("NON_UNIQUE")).thenAnswer(invocation -> !rows[cursor[0]].unique());
        return connection;
    }

    /** The connection mechanics the runner touches before it prepares its statements. */
    private static void stubTarget(Connection connection, boolean tableExists)
            throws SQLException {
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        if (tableExists) {
            when(statement.executeQuery(anyString())).thenReturn(mock(ResultSet.class));
        } else {
            when(statement.executeQuery(anyString()))
                    .thenThrow(new SQLException("table dst not found"));
        }
        when(connection.prepareStatement(anyString()))
                .thenAnswer(invocation -> mock(PreparedStatement.class));
    }

    private static EtlRunner runner() {
        return new EtlRunner(new SqlDialectFactory(List.of(), new GenericSqlDialect()),
                new MemoryCheckpoints(), (config, connections) -> new EtlSource() {
                    @Override
                    public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
                        return new EtlSourceCursor() {
                            @Override
                            public List<EtlSourceColumn> columns() {
                                return List.of(
                                        new EtlSourceColumn("id", Types.INTEGER, "INTEGER",
                                                null, null, true),
                                        new EtlSourceColumn("name", Types.VARCHAR, "VARCHAR",
                                                20, null, true));
                            }

                            @Override
                            public int resumeKeyIndex() {
                                return -1;
                            }

                            @Override
                            public boolean next() {
                                return false;
                            }

                            @Override
                            public Object getObject(int oneBasedIndex) {
                                return null;
                            }

                            @Override
                            public String getString(int oneBasedIndex) {
                                return null;
                            }

                            @Override
                            public void close() {
                            }
                        };
                    }

                    @Override
                    public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void close() {
                    }
                });
    }

    private static EtlTask upsertTask() {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceSql("SELECT id, name FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("id");
        return task;
    }

    private static DatabaseConfig source() {
        return config(1L, DatabaseType.H2);
    }

    private static DatabaseConfig mysqlTarget() {
        return config(2L, DatabaseType.MYSQL);
    }

    private static DatabaseConfig config(Long id, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(type);
        return config;
    }

    private static EtlConnectionProvider provider(Connection target) {
        return config -> target;
    }

    private record IndexRow(String index, String column, boolean unique) {
    }

    private static final class MemoryCheckpoints implements EtlCheckpointStore {
        private final Map<Long, EtlCheckpointValue> values = new HashMap<>();

        @Override
        public EtlCheckpointValue load(Long taskId) {
            return values.get(taskId);
        }

        @Override
        public void save(Long taskId, int jdbcType, String value) {
            values.put(taskId, new EtlCheckpointValue(jdbcType, value));
        }

        @Override
        public void clear(Long taskId) {
            values.remove(taskId);
        }
    }
}
