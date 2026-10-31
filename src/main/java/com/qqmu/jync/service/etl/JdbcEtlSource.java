package com.qqmu.jync.service.etl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;

/**
 * The JDBC implementation of an ETL source. It is the behavior the runner used to hold inline,
 * moved behind {@link EtlSource} without changing the SQL it sends or the values it binds.
 */
final class JdbcEtlSource implements EtlSource {

    private static final int FETCH_SIZE = 1000;

    private final Connection connection;
    private final DatabaseConfig config;

    JdbcEtlSource(Connection connection, DatabaseConfig config) {
        this.connection = connection;
        this.config = config;
        try {
            connection.setReadOnly(true);
        } catch (SQLException ignored) {
            // Not every driver accepts a read-only hint; the connection is only used for
            // SELECTs by construction.
        }
    }

    @Override
    public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) throws SQLException {
        List<String> statements = EtlSqlScript.split(task.getSourceSql());
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("error.etl.script.empty");
        }
        String select = statements.get(statements.size() - 1);
        List<String> leading = statements.subList(0, statements.size() - 1);
        boolean script = !leading.isEmpty();
        if (script) {
            // PostgreSQL refuses CREATE TEMP TABLE inside a read-only transaction; the source
            // account's own grants remain the guard against writes.
            allowSessionObjects();
        }

        // PostgreSQL needs a transaction for a cursor to stream; a read-only transaction is
        // harmless everywhere.
        connection.setAutoCommit(false);

        if (script) {
            runLeadingStatements(leading, task);
        }

        boolean resumable = resumable(task);
        String sql = sourceQuery(select, task, checkpoint, resumable);
        PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            statement.setFetchSize(fetchSize());
            Integer timeout = task.getTimeoutSeconds();
            if (timeout != null && timeout > 0) {
                statement.setQueryTimeout(timeout);
            }
            if (checkpoint != null && resumable) {
                bindResumeKey(statement, checkpoint);
            }
            ResultSet resultSet = statement.executeQuery();
            return new JdbcEtlSourceCursor(statement, resultSet, task, resumable);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(statement);
            throw e;
        }
    }

    @Override
    public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) throws SQLException {
        List<String> statements = EtlSqlScript.split(task.getSourceSql());
        String select = statements.isEmpty()
                ? EtlSqlSupport.stripTrailingSemicolon(task.getSourceSql())
                : statements.get(statements.size() - 1);
        boolean script = statements.size() > 1;
        if (script) {
            allowSessionObjects();
            // A precheck must not leave the source changed by the script it just prepared.
            connection.setAutoCommit(false);
        }
        try (Statement statement = connection.createStatement()) {
            // Keep the runner's timeout convention: the service always passes a positive value.
            statement.setQueryTimeout(timeoutSeconds);
            if (script) {
                runLeading(statement, statements.subList(0, statements.size() - 1));
            }
            try (ResultSet resultSet = statement.executeQuery(
                    EtlSqlSupport.zeroRowProbe(select))) {
                EtlSourceProbe probe = probe(resultSet, task);
                if (!script) {
                    return probe;
                }
                List<EtlSourceIssue> issues = new ArrayList<>(probe.issues());
                issues.add(0, new EtlSourceIssue(EtlPrecheckResult.Level.OK,
                        "etl.precheck.script.ok", String.valueOf(statements.size())));
                return new EtlSourceProbe(probe.columns(), probe.resumeKeyIndex(), issues);
            }
        } finally {
            if (script) {
                rollbackQuietly();
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection is about to be closed; nothing useful to do here.
        }
    }

    private static EtlSourceProbe probe(ResultSet resultSet, EtlTask task) throws SQLException {
        java.sql.ResultSetMetaData meta = resultSet.getMetaData();
        int count = meta.getColumnCount();
        List<EtlSourceColumn> columns = new ArrayList<>();
        List<EtlSourceIssue> issues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            // The runner writes unquoted, so a label the target cannot reproduce as a column
            // name would fail on the first INSERT, after the target was created.
            if (label == null || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(label).matches()) {
                issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.ERROR,
                        "etl.precheck.column.error", String.valueOf(label)));
                continue;
            }
            columns.add(column(meta, i, label));
        }
        issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.OK,
                "etl.precheck.source.ok", String.valueOf(count)));

        int keyIndex = -1;
        String key = task.getResumeKeyColumn();
        if (key != null && !key.isBlank()) {
            String trimmed = key.trim();
            try {
                keyIndex = resultSet.findColumn(trimmed);
                issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.OK,
                        "etl.precheck.resume.ok", trimmed));
            } catch (SQLException e) {
                // The runner looks the key up the same way; a mismatch here would stop it mid-run.
                issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.ERROR,
                        "etl.precheck.resume.error", trimmed));
            }
        }
        return new EtlSourceProbe(columns, keyIndex, issues);
    }

    private static EtlSourceColumn column(java.sql.ResultSetMetaData meta, int index, String label)
            throws SQLException {
        int precision = meta.getPrecision(index);
        int scale = meta.getScale(index);
        boolean nullable;
        try {
            nullable = meta.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls;
        } catch (SQLException e) {
            nullable = true;
        }
        return new EtlSourceColumn(label, meta.getColumnType(index), meta.getColumnTypeName(index),
                precision > 0 ? precision : null, scale >= 0 ? scale : null, nullable);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done at this point.
        }
    }

    // --- script ---------------------------------------------------------------------------

    private void allowSessionObjects() {
        try {
            connection.setReadOnly(false);
        } catch (SQLException ignored) {
            // Not every driver lets the mode change at this point; the source account's grants
            // still decide what the script can do.
        }
    }

    private void runLeadingStatements(List<String> leading, EtlTask task) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            Integer timeout = task.getTimeoutSeconds();
            if (timeout != null && timeout > 0) {
                statement.setQueryTimeout(timeout);
            }
            runLeading(statement, leading);
        }
    }

    private static void runLeading(Statement statement, List<String> leading) throws SQLException {
        for (String sql : leading) {
            if (statement.execute(sql)) {
                try (ResultSet rows = statement.getResultSet()) {
                    // A leading statement may return rows; nothing reads them.
                }
            }
        }
    }

    // --- query shape ----------------------------------------------------------------------

    private static boolean resumable(EtlTask task) {
        String key = task.getResumeKeyColumn();
        return task.getWriteMode() != EtlWriteMode.OVERWRITE && key != null && !key.isBlank();
    }

    private static String sourceQuery(String select, EtlTask task, EtlCheckpointValue checkpoint,
                                      boolean resumable) {
        String sql = select;
        String key = task.getResumeKeyColumn() == null
                ? null : task.getResumeKeyColumn().trim();
        if (resumable) {
            String wrapped = "SELECT * FROM (" + sql + ") " + EtlSqlSupport.SUBQUERY_ALIAS;
            if (checkpoint != null) {
                wrapped += " WHERE " + key + " > ?";
            }
            wrapped += " ORDER BY " + key;
            sql = wrapped;
        }
        return sql;
    }

    /**
     * Connector/J only streams when the fetch size is {@link Integer#MIN_VALUE}; a positive
     * value buffers the whole result in the driver. Everything else takes a normal fetch size.
     */
    private int fetchSize() {
        DatabaseType type = config == null ? null : config.effectiveType();
        return type != null && type.getFamily() == DatabaseType.DialectFamily.MYSQL
                ? Integer.MIN_VALUE : FETCH_SIZE;
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

    private static void closeQuietly(Statement statement) {
        try {
            statement.close();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }
}
