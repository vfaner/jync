package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * Answers "would this ETL run get past its first row?" without writing anything to either side.
 *
 * <p>It reuses the runner's SQL-shape rules ({@link EtlSqlSupport}) and configuration checks
 * ({@link EtlTaskValidation}) on purpose. A precheck that accepts what the runner rejects — or
 * the reverse — teaches the operator the wrong lesson, which is worse than having no precheck.
 *
 * <p>Checks are data, not exceptions: every SQL or connection problem becomes a
 * {@link EtlPrecheckResult.Check} so the page can show all of them at once. Only an invalid task
 * definition (same database, bad table name, missing range condition) throws, exactly as save does.
 */
@Service
public class EtlPrecheckService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int MAX_TIMEOUT_SECONDS = 60;

    private final DatabaseConfigService databaseConfigService;
    private final DataSourceManager dataSourceManager;

    public EtlPrecheckService(DatabaseConfigService databaseConfigService,
                              DataSourceManager dataSourceManager) {
        this.databaseConfigService = databaseConfigService;
        this.dataSourceManager = dataSourceManager;
    }

    /** Prechecks a form that has not been saved yet. */
    public EtlPrecheckResult check(EtlTask form) {
        EtlTaskValidation.validateExtraction(form, databaseConfigService);
        DatabaseConfig source = databaseConfigService.findById(form.getSourceDbId())
                .orElseThrow(() -> new IllegalArgumentException("error.etl.source.missing"));
        DatabaseConfig target = databaseConfigService.findById(form.getTargetDbId())
                .orElseThrow(() -> new IllegalArgumentException("error.etl.target.missing"));
        try (EtlConnections connections = new EtlConnections(dataSourceManager)) {
            return check(form, source, target, connections);
        }
    }

    EtlPrecheckResult check(EtlTask task, DatabaseConfig sourceConfig,
                            DatabaseConfig targetConfig, EtlConnectionProvider connections) {
        List<EtlPrecheckResult.Check> checks = new ArrayList<>();
        List<EtlPrecheckResult.ColumnInfo> columns = new ArrayList<>();
        int timeout = timeout(task.getTimeoutSeconds());

        Map<String, String> resultColumns = null;
        Connection source = null;
        try {
            source = connections.open(sourceConfig);
            markReadOnly(source);
            resultColumns = probeSource(source, task, timeout, checks, columns);
        } catch (SQLException e) {
            checks.add(error("etl.precheck.connection.error", message(e)));
        } finally {
            closeQuietly(source);
        }

        Connection target = null;
        try {
            target = connections.open(targetConfig);
            probeTarget(target, task, targetConfig, timeout, checks, resultColumns);
        } catch (SQLException e) {
            checks.add(error("etl.precheck.connection.error", message(e)));
        } finally {
            closeQuietly(target);
        }

        return new EtlPrecheckResult(checks, columns);
    }

    // --- source ---------------------------------------------------------------------------

    /**
     * Runs the extraction SQL behind a zero-row wrapper and reads its metadata.
     *
     * @return the result columns by folded name, or {@code null} when the SQL itself failed and
     *         the target comparison has nothing to compare against
     */
    private Map<String, String> probeSource(Connection source, EtlTask task, int timeout,
                                            List<EtlPrecheckResult.Check> checks,
                                            List<EtlPrecheckResult.ColumnInfo> columns) {
        try (Statement statement = source.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(
                    EtlSqlSupport.zeroRowProbe(task.getSourceSql()))) {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                Map<String, String> names = new LinkedHashMap<>();
                for (int i = 1; i <= count; i++) {
                    String label = meta.getColumnLabel(i);
                    // The runner writes unquoted, so a label the target cannot reproduce as a
                    // column name would fail on the first INSERT, after the target was created.
                    if (label == null
                            || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(label).matches()) {
                        checks.add(error("etl.precheck.column.error", String.valueOf(label)));
                        continue;
                    }
                    names.putIfAbsent(label.toLowerCase(Locale.ROOT), label);
                    columns.add(new EtlPrecheckResult.ColumnInfo(label, typeName(meta, i)));
                }
                checks.add(ok("etl.precheck.source.ok", String.valueOf(count)));
                checkResumeKey(rs, task, checks);
                return names;
            }
        } catch (SQLException e) {
            checks.add(error("etl.precheck.source.error", message(e)));
            return null;
        }
    }

    private static void checkResumeKey(ResultSet rs, EtlTask task,
                                       List<EtlPrecheckResult.Check> checks) {
        String key = task.getResumeKeyColumn();
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        try {
            rs.findColumn(trimmed);
            checks.add(ok("etl.precheck.resume.ok", trimmed));
        } catch (SQLException e) {
            // The runner looks the key up the same way; a mismatch here would stop it mid-run.
            checks.add(error("etl.precheck.resume.error", trimmed));
        }
    }

    // --- target ---------------------------------------------------------------------------

    private void probeTarget(Connection target, EtlTask task, DatabaseConfig targetConfig,
                             int timeout, List<EtlPrecheckResult.Check> checks,
                             Map<String, String> resultColumns) {
        String name = EtlSqlSupport.qualified(targetConfig, task.getTargetTable());
        if (!EtlSqlSupport.exists(target, name, timeout)) {
            // Creating the table is the runner's first step, so this is information, not a fault.
            checks.add(ok("etl.precheck.target.missing", name));
            if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE) {
                checks.add(ok("etl.precheck.range.skipped", null));
            }
            return;
        }
        checks.add(ok("etl.precheck.target.ok", name));

        if (resultColumns == null) {
            return;
        }
        Map<String, TargetColumn> targetColumns = targetColumns(target, name, timeout, checks);
        if (targetColumns == null) {
            return;
        }
        compareColumns(resultColumns, targetColumns, checks);
        checkRangeCondition(target, name, task, timeout, checks);
    }

    private Map<String, TargetColumn> targetColumns(Connection target, String name, int timeout,
                                                    List<EtlPrecheckResult.Check> checks) {
        try (Statement statement = target.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT * FROM " + name + " WHERE 1 = 0")) {
                ResultSetMetaData meta = rs.getMetaData();
                Map<String, TargetColumn> columns = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String label = meta.getColumnLabel(i);
                    columns.put(label.toLowerCase(Locale.ROOT),
                            new TargetColumn(label, meta.isNullable(i) != ResultSetMetaData.columnNoNulls));
                }
                return columns;
            }
        } catch (SQLException e) {
            checks.add(error("etl.precheck.target.columns.error", message(e)));
            return null;
        }
    }

    private static void compareColumns(Map<String, String> resultColumns,
                                       Map<String, TargetColumn> targetColumns,
                                       List<EtlPrecheckResult.Check> checks) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : resultColumns.entrySet()) {
            if (!targetColumns.containsKey(entry.getKey())) {
                missing.add(entry.getValue());
            }
        }
        if (!missing.isEmpty()) {
            checks.add(error("etl.precheck.target.columns.error", String.join(", ", missing)));
        }

        // Not an error: the customer may fill these with defaults or in a later step, but the
        // first row will be rejected unless they are nullable or defaulted.
        List<String> notNull = new ArrayList<>();
        for (Map.Entry<String, TargetColumn> entry : targetColumns.entrySet()) {
            if (!resultColumns.containsKey(entry.getKey()) && !entry.getValue().nullable()) {
                notNull.add(entry.getValue().label());
            }
        }
        if (!notNull.isEmpty()) {
            checks.add(warn("etl.precheck.target.notNull", String.join(", ", notNull)));
        }
    }

    private static void checkRangeCondition(Connection target, String name, EtlTask task,
                                            int timeout, List<EtlPrecheckResult.Check> checks) {
        if (task.getWriteMode() != EtlWriteMode.RANGE_OVERWRITE
                || task.getRangeCondition() == null || task.getRangeCondition().isBlank()) {
            return;
        }
        String sql = "SELECT 1 FROM " + name
                + " WHERE (" + task.getRangeCondition().trim() + ") AND 1 = 0";
        try (Statement statement = target.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(sql)) {
                checks.add(ok("etl.precheck.range.ok", null));
            }
        } catch (SQLException e) {
            checks.add(error("etl.precheck.range.error", message(e)));
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private static int timeout(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(configured, MAX_TIMEOUT_SECONDS);
    }

    private static String typeName(ResultSetMetaData meta, int index) {
        try {
            return meta.getColumnTypeName(index);
        } catch (SQLException e) {
            return "";
        }
    }

    private static String message(SQLException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static void markReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException ignored) {
            // A driver that refuses the hint can still run the probing SELECT.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A leak at most; nothing useful to do about it here.
        }
    }

    private static EtlPrecheckResult.Check ok(String code, String detail) {
        return new EtlPrecheckResult.Check(EtlPrecheckResult.Level.OK, code, detail);
    }

    private static EtlPrecheckResult.Check warn(String code, String detail) {
        return new EtlPrecheckResult.Check(EtlPrecheckResult.Level.WARN, code, detail);
    }

    private static EtlPrecheckResult.Check error(String code, String detail) {
        return new EtlPrecheckResult.Check(EtlPrecheckResult.Level.ERROR, code, detail);
    }

    private record TargetColumn(String label, boolean nullable) {
    }
}
