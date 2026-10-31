package com.qqmu.jync.service.etl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.etl.EtlRunResult.Side;

/**
 * Copies the rows of a customer-authored query into a target table.
 *
 * <p>This is deliberately not the real-time sync engine: no cursor probing, no CDC, no
 * structure comparison. The customer owns the extraction SQL and the target table; Jync only
 * streams the result, creates the target table once from the result metadata when it is
 * missing, and reports which side failed. See {@link EtlWriteMode} for what is deleted.
 */
@Service
public class EtlRunner {

    private static final Logger log = LoggerFactory.getLogger(EtlRunner.class);

    private static final int BATCH_SIZE = 500;
    private static final int FETCH_SIZE = 1000;

    private final SqlDialectFactory dialectFactory;
    private final EtlCheckpointStore checkpointStore;

    public EtlRunner(SqlDialectFactory dialectFactory, EtlCheckpointStore checkpointStore) {
        this.dialectFactory = dialectFactory;
        this.checkpointStore = checkpointStore;
    }

    public EtlRunResult run(EtlTask task, DatabaseConfig sourceConfig,
                            DatabaseConfig targetConfig, EtlConnectionProvider connections) {
        long rowsRead = 0;
        long rowsWritten = 0;
        Connection source = null;
        Connection target = null;
        Side stage = Side.PROGRAM;

        try {
            validate(task);

            DatabaseType sourceType = sourceConfig.effectiveType();
            SqlDialect targetDialect = dialectFactory.forType(
                    targetConfig.effectiveType(), null, targetConfig.resolvedFamily());
            boolean resumable = task.getWriteMode() != EtlWriteMode.OVERWRITE
                    && notBlank(task.getResumeKeyColumn());
            EtlCheckpointValue checkpoint = resumable
                    ? checkpointStore.load(task.getId()) : null;
            if (task.getWriteMode() == EtlWriteMode.OVERWRITE) {
                checkpointStore.clear(task.getId());
            }

            stage = Side.PROGRAM;
            source = connections.open(sourceConfig);
            target = connections.open(targetConfig);
            prepareSource(source);
            target.setAutoCommit(false);

            // Execute pre-sql (cleanup, deletions, pre-transformations)
            if (task.getPreSql() != null && !task.getPreSql().isBlank()) {
                executePreOrPost(target, task, task.getPreSql());
            }

            String targetName = EtlSqlSupport.qualified(targetConfig, task.getTargetTable());

            stage = Side.SOURCE;
            try (PreparedStatement query = sourceQuery(source, task, checkpoint, sourceConfig)) {
                if (checkpoint != null) {
                    bindResumeKey(query, checkpoint);
                }
                stage = Side.SOURCE;
                try (ResultSet rs = query.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columns = meta.getColumnCount();
                    List<String> columnNames = columnNames(meta);
                    // Resolve the key before anything on the target is deleted: a typo must
                    // not cost the operator the previous contents.
                    int keyIndex = resumeKeyIndex(rs, task, resumable);

                    stage = Side.TARGET;
                    ensureTargetTable(target, targetDialect, sourceType, targetConfig,
                            task.getTargetTable(), targetName, meta, columnNames);
                    deleteForWriteMode(target, task, targetName, checkpoint);
                    String insertSql = insertSql(targetName, columnNames);
                    stage = Side.TARGET;
                    try (PreparedStatement insert = target.prepareStatement(insertSql)) {
                        int pending = 0;
                        int keyType = Types.NULL;
                        String keyValue = null;
                        Integer timeout = task.getTimeoutSeconds();

                        stage = Side.SOURCE;
                        while (rs.next()) {
                            stage = Side.SOURCE;
                            rowsRead++;
                            Object[] values = new Object[columns];
                            for (int i = 1; i <= columns; i++) {
                                values[i - 1] = rs.getObject(i);
                            }
                            if (keyIndex > 0) {
                                keyType = meta.getColumnType(keyIndex);
                                keyValue = rs.getString(keyIndex);
                            }
                            stage = Side.TARGET;
                            for (int i = 1; i <= columns; i++) {
                                Object value = values[i - 1];
                                if (value == null) {
                                    insert.setNull(i, meta.getColumnType(i));
                                } else {
                                    insert.setObject(i, value);
                                }
                            }
                            if (timeout != null && timeout > 0) {
                                insert.setQueryTimeout(timeout);
                            }
                            insert.addBatch();
                            pending++;

                            if (pending >= BATCH_SIZE) {
                                stage = Side.TARGET;
                                insert.executeBatch();
                                target.commit();
                                rowsWritten += pending;
                                pending = 0;
                                stage = Side.PROGRAM;
                                saveCheckpoint(task, resumable, keyType, keyValue);
                            }
                        }

                        if (pending > 0) {
                            stage = Side.TARGET;
                            insert.executeBatch();
                            target.commit();
                            rowsWritten += pending;
                            stage = Side.PROGRAM;
                            saveCheckpoint(task, resumable, keyType, keyValue);
                        }
                    }
                }
            }

            log.info("ETL task {} copied {} rows into {} ({} read)",
                    task.getId(), rowsWritten, targetName, rowsRead);

            // Execute post-sql (updates, calculations, post-processing)
            if (task.getPostSql() != null && !task.getPostSql().isBlank()) {
                executePreOrPost(target, task, task.getPostSql());
            }

            return EtlRunResult.success(rowsRead, rowsWritten);
        } catch (EtlProgramException e) {
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.PROGRAM, e.getMessage());
        } catch (SQLException e) {
            log.warn("ETL task {} failed on the {} side: {}", task.getId(), stage, e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, stage, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("ETL task {} failed in the program: {}", task.getId(), e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.PROGRAM, String.valueOf(e.getMessage()));
        } finally {
            closeQuietly(source);
            closeQuietly(target);
        }
    }

    // --- preparation --------------------------------------------------------------------

    private static void validate(EtlTask task) {
        if (task == null) {
            throw new EtlProgramException("task is missing");
        }
        if (notBlank(task.getResumeKeyColumn())
                && !EtlSqlSupport.RESUME_KEY.matcher(task.getResumeKeyColumn().trim()).matches()) {
            throw new EtlProgramException(
                    "resume key must be a plain column name: " + task.getResumeKeyColumn());
        }
        if (!notBlank(task.getSourceSql())) {
            throw new EtlProgramException("extraction SQL is empty");
        }
        String table = task.getTargetTable();
        if (!notBlank(table)) {
            throw new EtlProgramException("target table is empty");
        }
        for (String part : table.trim().split("\\.")) {
            if (!EtlSqlSupport.PLAIN_IDENTIFIER.matcher(part).matches()) {
                throw new EtlProgramException("invalid target table name: " + table);
            }
        }
        if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE
                && !notBlank(task.getRangeCondition())) {
            throw new EtlProgramException(
                    "a range overwrite needs a range condition on the target table");
        }
    }

    private static void prepareSource(Connection source) throws SQLException {
        try {
            source.setReadOnly(true);
        } catch (SQLException ignored) {
            // Not every driver accepts a read-only hint; the connection is only used for
            // SELECTs by construction.
        }
        // PostgreSQL needs a transaction for a cursor to stream; a read-only transaction is
        // harmless everywhere.
        source.setAutoCommit(false);
    }

    private PreparedStatement sourceQuery(Connection source, EtlTask task,
                                          EtlCheckpointValue checkpoint,
                                          DatabaseConfig sourceConfig) throws SQLException {
        String sql = EtlSqlSupport.stripTrailingSemicolon(task.getSourceSql());
        String key = task.getResumeKeyColumn() == null
                ? null : task.getResumeKeyColumn().trim();
        if (key != null && !key.isEmpty() && task.getWriteMode() != EtlWriteMode.OVERWRITE) {
            String wrapped = "SELECT * FROM (" + sql + ") " + EtlSqlSupport.SUBQUERY_ALIAS;
            if (checkpoint != null) {
                wrapped += " WHERE " + key + " > ?";
            }
            wrapped += " ORDER BY " + key;
            sql = wrapped;
        }

        PreparedStatement statement = source.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(fetchSize(sourceConfig));
        Integer timeout = task.getTimeoutSeconds();
        if (timeout != null && timeout > 0) {
            statement.setQueryTimeout(timeout);
        }
        return statement;
    }

    /**
     * Connector/J only streams when the fetch size is {@link Integer#MIN_VALUE}; a positive
     * value buffers the whole result in the driver. Everything else takes a normal fetch size.
     */
    private static int fetchSize(DatabaseConfig sourceConfig) {
        DatabaseType type = sourceConfig.effectiveType();
        return type != null && type.getFamily() == DatabaseType.DialectFamily.MYSQL
                ? Integer.MIN_VALUE : FETCH_SIZE;
    }

    private static int resumeKeyIndex(ResultSet rs, EtlTask task, boolean resumable)
            throws SQLException {
        if (!resumable) {
            return -1;
        }
        try {
            return rs.findColumn(task.getResumeKeyColumn().trim());
        } catch (SQLException e) {
            throw new EtlProgramException("resume key '" + task.getResumeKeyColumn()
                    + "' is not a column of the extraction query");
        }
    }

    private static List<String> columnNames(ResultSetMetaData meta) throws SQLException {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(label).matches()) {
                throw new EtlProgramException("result column '" + label
                        + "' is not a plain identifier; alias it in the extraction SQL");
            }
            names.add(label);
        }
        return names;
    }

    // --- target -------------------------------------------------------------------------

    private void ensureTargetTable(Connection target, SqlDialect dialect, DatabaseType sourceType,
                                   DatabaseConfig targetConfig, String configuredTable,
                                   String targetName, ResultSetMetaData meta,
                                   List<String> columnNames) throws SQLException {
        if (EtlSqlSupport.exists(target, targetName)) {
            return;
        }
        TableMeta table = new TableMeta();
        table.setName(configuredTable);
        for (int i = 1; i <= columnNames.size(); i++) {
            ColumnMeta column = new ColumnMeta();
            column.setName(columnNames.get(i - 1));
            column.setJdbcType(meta.getColumnType(i));
            column.setTypeName(meta.getColumnTypeName(i));
            column.setSize(nullableSize(meta.getPrecision(i)));
            int scale = meta.getScale(i);
            column.setDecimalDigits(scale >= 0 ? scale : null);
            try {
                column.setNullable(meta.isNullable(i) != ResultSetMetaData.columnNoNulls);
            } catch (SQLException ignored) {
                column.setNullable(true);
            }
            column.setOrdinalPosition(i);
            table.getColumns().add(column);
        }

        // Deliberately assembled here instead of via getCreateTableSql: the target table name
        // and the result labels are customer-facing identifiers, and the dialect quotes what
        // it is given. Quoting would create a case-sensitive table that the customer's own
        // unquoted SQL (and our unquoted INSERT) could no longer reach on products that fold
        // identifiers to upper case.
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(targetName).append(" (");
        for (int i = 0; i < table.getColumns().size(); i++) {
            ColumnMeta column = table.getColumns().get(i);
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(column.getName()).append(' ')
                    .append(dialect.mapType(column, sourceType));
            if (!column.isNullable()) {
                ddl.append(" NOT NULL");
            }
        }
        ddl.append(')');

        try (Statement statement = target.createStatement()) {
            statement.execute(ddl.toString());
            target.commit();
            log.info("ETL created target table {} from the extraction result metadata", targetName);
        }
    }

    private static Integer nullableSize(int precision) {
        return precision > 0 ? precision : null;
    }

    private void deleteForWriteMode(Connection target, EtlTask task, String targetName,
                                    EtlCheckpointValue checkpoint) throws SQLException {
        if (task.getWriteMode() == EtlWriteMode.OVERWRITE) {
            execute(target, "DELETE FROM " + targetName);
        } else if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE && checkpoint == null) {
            // A continuation run must not delete: the first run already removed the range and
            // committed part of its replacement.
            execute(target, "DELETE FROM " + targetName
                    + " WHERE " + task.getRangeCondition().trim());
        }
    }

    private static void execute(Connection target, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.executeUpdate(sql);
            target.commit();
        }
    }

    /** Execute arbitrary SQL in the target connection, within the current transaction. */
    private void executePreOrPost(Connection target, EtlTask task, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute(sql);
            // Commit immediately for pre/post SQL to take effect; they are not part of the
            // batch insert transaction, but must succeed before we commit the data load.
            target.commit();
            log.info("Executed custom SQL in ETL task {}", task.getId());
        } catch (SQLException e) {
            log.warn("ETL task {} failed to execute custom SQL: {}", task.getId(), e.getMessage());
            target.rollback();
            throw e;
        }
    }

    private static String insertSql(String targetName, List<String> columns) {
        String names = String.join(", ", columns);
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(columns.size(), "?"));
        return "INSERT INTO " + targetName + " (" + names + ") VALUES (" + placeholders + ")";
    }

    // --- checkpoint ---------------------------------------------------------------------

    private void saveCheckpoint(EtlTask task, boolean resumable, int keyType, String keyValue) {
        if (!resumable || keyValue == null) {
            return;
        }
        checkpointStore.save(task.getId(), keyType, keyValue);
    }

    private static void bindResumeKey(PreparedStatement statement, EtlCheckpointValue checkpoint)
            throws SQLException {
        String value = checkpoint.value();
        try {
            switch (checkpoint.jdbcType()) {
                case Types.TINYINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                case Types.BIGINT:
                    statement.setLong(1, Long.parseLong(value));
                    break;
                case Types.NUMERIC:
                case Types.DECIMAL:
                    statement.setBigDecimal(1, new BigDecimal(value));
                    break;
                case Types.REAL:
                case Types.FLOAT:
                case Types.DOUBLE:
                    statement.setDouble(1, Double.parseDouble(value));
                    break;
                case Types.DATE:
                    statement.setDate(1, Date.valueOf(value));
                    break;
                case Types.TIME:
                    statement.setTime(1, Time.valueOf(value));
                    break;
                case Types.TIMESTAMP:
                    statement.setTimestamp(1, Timestamp.valueOf(value));
                    break;
                default:
                    statement.setString(1, value);
                    break;
            }
        } catch (IllegalArgumentException e) {
            throw new EtlProgramException("stored resume value '" + value
                    + "' cannot be read back as JDBC type " + checkpoint.jdbcType());
        }
    }

    // --- helpers ------------------------------------------------------------------------

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void safeRollback(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection may already be broken; closing it is the only remaining step.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done at this point.
        }
    }

    /** A defect in the task configuration or in Jync itself, not a database failure. */
    private static final class EtlProgramException extends RuntimeException {
        EtlProgramException(String message) {
            super(message);
        }
    }
}
