package com.qqmu.jync.service.migration.kingbase;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.SqlDialect;

/**
 * The small piece of JDBC that a MongoDB → KingbaseES run needs: create, drop, count, inspect
 * and batch-insert one table.
 *
 * <p>Every identifier goes through the dialect's {@code qualify}/{@code quoteIdentifier};
 * values are bound as parameters. DDL and every batch are committed separately, because the
 * migration deliberately has no cross-table transaction.
 */
public class KingbaseTableWriter {

    /** The source product the type mapping is asked about; BSON columns carry JDBC types. */
    private static final DatabaseType SOURCE = DatabaseType.MONGODB_NATIVE;

    private final Connection connection;
    private final String schema;
    private final SqlDialect dialect;

    public KingbaseTableWriter(Connection connection, String schema, SqlDialect dialect) {
        this.connection = connection;
        this.schema = schema;
        this.dialect = dialect;
    }

    public String qualified(String table) {
        return dialect.qualify(schema, table);
    }

    public boolean tableExists(String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(null, escape(metaData, schema),
                escape(metaData, table), new String[] {"TABLE"})) {
            return resultSet.next();
        }
    }

    /** True when the schema this writer targets exists on the server. */
    public boolean schemaExists() throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getSchemas()) {
            boolean anyReported = false;
            while (resultSet.next()) {
                anyReported = true;
                if (schema.equals(resultSet.getString("TABLE_SCHEM"))) {
                    return true;
                }
            }
            // A driver that reports no schemas at all cannot answer; do not block on it.
            return !anyReported;
        }
    }

    public void dropTable(String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + qualified(table));
        }
        connection.commit();
    }

    public void createTable(TableMeta table, String targetTable) throws SQLException {
        execute(dialect.getCreateTableSql(table, schema, targetTable, SOURCE));
    }

    /**
     * Rolls back the current transaction, ignoring a failure so the original error survives.
     *
     * <p>PostgreSQL-family servers mark the transaction aborted after any failed statement;
     * without this, one failed collection would make every later collection fail with "current
     * transaction is aborted" even when the run asked to continue on error.
     */
    public void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection is already unusable; the statement error that led here is the one
            // worth reporting.
        }
    }

    public long countRows(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + qualified(table))) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    public List<ColumnMeta> targetColumns(String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<ColumnMeta> columns = new ArrayList<>();
        try (ResultSet resultSet = metaData.getColumns(null, escape(metaData, schema),
                escape(metaData, table), "%")) {
            while (resultSet.next()) {
                ColumnMeta column = new ColumnMeta();
                column.setName(resultSet.getString("COLUMN_NAME"));
                column.setJdbcType(resultSet.getInt("DATA_TYPE"));
                column.setTypeName(resultSet.getString("TYPE_NAME"));
                int size = resultSet.getInt("COLUMN_SIZE");
                column.setSize(size > 0 ? size : null);
                int scale = resultSet.getInt("DECIMAL_DIGITS");
                column.setDecimalDigits(scale > 0 ? scale : null);
                column.setNullable(!"NO".equalsIgnoreCase(resultSet.getString("IS_NULLABLE")));
                column.setDefaultValue(resultSet.getString("COLUMN_DEF"));
                column.setOrdinalPosition(columns.size() + 1);
                columns.add(column);
            }
        }
        return columns;
    }

    public boolean idExists(String table, ColumnMeta idColumn, Object renderedId)
            throws SQLException {
        String sql = "SELECT 1 FROM " + qualified(table) + " WHERE "
                + dialect.quoteIdentifier(idColumn.getName()) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValue(statement, 1, renderedId, idColumn.getJdbcType());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /** True when the table has a unique index or primary key that covers {@code column}. */
    public boolean hasUniqueIndex(String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(null, escape(metaData, schema),
                escape(metaData, table), true, true)) {
            while (resultSet.next()) {
                if (column.equals(resultSet.getString("COLUMN_NAME"))
                        && !resultSet.getBoolean("NON_UNIQUE")) {
                    return true;
                }
            }
        }
        return false;
    }

    public BatchWriter openBatch(String table, TableMeta meta, boolean upsertOnId)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        List<Integer> jdbcTypes = new ArrayList<>();
        for (ColumnMeta column : meta.getColumns()) {
            columns.add(column.getName());
            jdbcTypes.add(column.getJdbcType());
        }
        List<String> conflictColumns = upsertOnId ? List.of("_id") : List.of();
        String sql = dialect.getUpsertSql(schema, table, columns, conflictColumns);
        return new BatchWriter(connection, connection.prepareStatement(sql), jdbcTypes);
    }

    /**
     * Escapes metadata-pattern wildcards in an identifier. Collection names legitimately
     * contain {@code _}, which the metadata API would otherwise treat as "any character".
     */
    private static String escape(DatabaseMetaData metaData, String value) throws SQLException {
        if (value == null) {
            return null;
        }
        String escape = metaData.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    private static void bindValue(PreparedStatement statement, int parameter, Object value,
                                  int jdbcType) throws SQLException {
        if (value == null) {
            // PostgreSQL maps Types.BLOB to its large-object oid type; VARBINARY is the JDBC
            // constant that reaches a BYTEA column.
            int nullType = jdbcType == Types.BLOB ? Types.VARBINARY : jdbcType;
            statement.setNull(parameter, nullType);
        } else if (jdbcType == Types.BLOB && value instanceof byte[] bytes) {
            // setObject(byte[]) reaches PostgreSQL as an oid parameter, which BYTEA rejects;
            // the dedicated setter binds the column's real type.
            statement.setBytes(parameter, bytes);
        } else {
            statement.setObject(parameter, value);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        connection.commit();
    }

    /** One prepared statement's batch; each flush commits on its own. */
    public static class BatchWriter implements AutoCloseable {

        private final Connection connection;
        private final PreparedStatement statement;
        private final List<Integer> jdbcTypes;
        private int pending;

        private BatchWriter(Connection connection, PreparedStatement statement,
                            List<Integer> jdbcTypes) {
            this.connection = connection;
            this.statement = statement;
            this.jdbcTypes = jdbcTypes;
        }

        public void add(List<Object> values) throws SQLException {
            for (int i = 0; i < values.size(); i++) {
                bindValue(statement, i + 1, values.get(i), jdbcTypes.get(i));
            }
            statement.addBatch();
            pending++;
        }

        /** Executes and commits the accumulated rows; returns how many were sent. */
        public int flush() throws SQLException {
            if (pending == 0) {
                return 0;
            }
            try {
                statement.executeBatch();
            } catch (SQLException e) {
                connection.rollback();
                statement.clearBatch();
                pending = 0;
                throw e;
            }
            statement.clearBatch();
            connection.commit();
            int written = pending;
            pending = 0;
            return written;
        }

        @Override
        public void close() {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // The statement belongs to a connection the caller closes.
            }
        }
    }
}
