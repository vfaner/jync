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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.service.sync.JdbcRowMapper;

/**
 * The JDBC implementation of an ETL source. It is the behavior the runner used to hold inline,
 * moved behind {@link EtlSource} without changing the SQL it sends or the values it binds.
 */
final class JdbcEtlSource implements EtlSource {

    /** The one TIME rendering {@link #parsesBack} treats as faithful. */
    private static final java.time.format.DateTimeFormatter TIME_TEXT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

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
        List<String> statements = EtlSqlScript.split(task.getSourceSql(), scriptFamily());
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
        if (checkpoint != null && resumable) {
            requireUniqueBoundary(select, task, checkpoint);
        }
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
                bindResumeKey(statement, checkpoint, config == null ? null : config.effectiveType());
            }
            ResultSet resultSet = statement.executeQuery();
            return new JdbcEtlSourceCursor(statement, resultSet, task, resumable,
                    config == null ? null : config.effectiveType());
        } catch (SQLException | RuntimeException e) {
            closeQuietly(statement);
            throw e;
        }
    }

    @Override
    public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) throws SQLException {
        List<String> statements = EtlSqlScript.split(task.getSourceSql(), scriptFamily());
        String select = statements.isEmpty()
                ? EtlSqlSupport.stripTrailingSemicolon(task.getSourceSql())
                : statements.get(statements.size() - 1);
        boolean script = statements.size() > 1;
        if (script) {
            allowSessionObjects();
            // Run the script inside a transaction so the rollback below can undo it. That is
            // best effort only: MySQL-family DDL commits implicitly, so a script that changes
            // the source may leave it changed — the caller is warned, not reassured.
            connection.setAutoCommit(false);
        }
        try (Statement statement = connection.createStatement()) {
            // Keep the runner's timeout convention: the service always passes a positive value.
            statement.setQueryTimeout(timeoutSeconds);
            if (script) {
                runLeading(statement, statements.subList(0, statements.size() - 1));
            }
            DatabaseType.DialectFamily family = family();
            String probeSql = EtlSqlSupport.zeroRowProbe(select, family);
            if (!resumable(task) && EtlSqlSupport.derivedTableRejects(select, family)) {
                // The run leaves a non-resumable extraction unwrapped, so the precheck must
                // not be the one refusing a statement the run would accept.
                statement.setMaxRows(1);
                probeSql = select;
            }
            EtlSourceProbe probe;
            try (ResultSet resultSet = statement.executeQuery(probeSql)) {
                probe = probe(resultSet, task);
            }
            if (resumable(task) && probe.resumeKeyIndex() > 0) {
                List<EtlSourceIssue> issues = new ArrayList<>(probe.issues());
                checkDuplicateResumeKey(statement, select, task, family, issues);
                probe = new EtlSourceProbe(probe.columns(), probe.resumeKeyIndex(), issues);
            }
            if (!script) {
                return probe;
            }
            List<EtlSourceIssue> issues = new ArrayList<>(probe.issues());
            issues.add(0, new EtlSourceIssue(EtlPrecheckResult.Level.OK,
                    "etl.precheck.script.ok", String.valueOf(statements.size())));
            // Leading statements executed against the real source, and the rollback cannot
            // be relied on for DDL. The operator has to know the precheck may have left
            // the source changed.
            issues.add(1, new EtlSourceIssue(EtlPrecheckResult.Level.WARN,
                    "etl.precheck.script.sideEffects",
                    String.valueOf(statements.size() - 1)));
            return new EtlSourceProbe(probe.columns(), probe.resumeKeyIndex(), issues);
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
        // Two labels that fold together cannot both appear in the unquoted INSERT, and the
        // failure would land after the write-mode DELETE; the runner refuses the same shape.
        Set<String> folded = new HashSet<>();
        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            // The runner writes unquoted, so a label the target cannot reproduce as a column
            // name would fail on the first INSERT, after the target was created.
            if (label == null || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(label).matches()) {
                issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.ERROR,
                        "etl.precheck.column.error", String.valueOf(label)));
                continue;
            }
            if (!folded.add(label.toLowerCase(Locale.ROOT))) {
                issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.ERROR,
                        "etl.precheck.column.duplicate", label));
                continue;
            }
            columns.add(column(meta, i, label));
        }
        issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.OK,
                "etl.precheck.source.ok", String.valueOf(count)));

        int keyIndex = -1;
        String key = task.getResumeKeyColumn();
        // Only the modes that resume read the key. The form hides the field for the others
        // but still submits it, so a leftover name must not fail a precheck over a check the
        // run never performs — save and the runner already ignore it.
        if (resumable(task) && key != null && !key.isBlank()) {
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

    /**
     * Runs the precheck's duplicate-key scan. It reads the whole extraction, so a timeout or a
     * dialect quirk is reported as "could not verify" rather than blocking a manual precheck;
     * the runner's boundary check still protects the actual resume.
     */
    private static void checkDuplicateResumeKey(Statement statement, String select, EtlTask task,
                                                DatabaseType.DialectFamily family,
                                                List<EtlSourceIssue> issues) {
        String key = task.getResumeKeyColumn().trim();
        try {
            statement.setMaxRows(1);
            try (ResultSet duplicates = statement.executeQuery(
                    EtlSqlSupport.duplicateKeyScan(select, key, family))) {
                if (duplicates.next()) {
                    issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.ERROR,
                            "etl.precheck.resume.duplicate", key));
                }
            }
        } catch (SQLException e) {
            issues.add(new EtlSourceIssue(EtlPrecheckResult.Level.WARN,
                    "etl.precheck.resume.unverified", message(e)));
        }
    }

    private static String message(SQLException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
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

    private DatabaseType.DialectFamily family() {
        return config == null ? null : config.resolvedFamily();
    }

    /**
     * The family whose lexical rules split the script. A source constructed without a
     * configuration keeps the historic MySQL reading rather than aiming at a family nobody
     * declared; every producer passes a real configuration.
     */
    private DatabaseType.DialectFamily scriptFamily() {
        return config == null ? DatabaseType.DialectFamily.MYSQL : config.resolvedFamily();
    }

    private static boolean resumable(EtlTask task) {
        String key = task.getResumeKeyColumn();
        return task.getWriteMode() != null && task.getWriteMode().supportsResume()
                && key != null && !key.isBlank();
    }

    /**
     * Refuses to resume from a checkpoint whose key value still occurs more than once.
     *
     * <p>The continuation filters {@code key > ?}, so it cannot tell the rows of that value it
     * already copied from the ones it did not. Failing here costs a rerun; guessing would cost
     * the rows silently, which is the one outcome an ETL is not allowed to produce.
     */
    private void requireUniqueBoundary(String select, EtlTask task, EtlCheckpointValue checkpoint)
            throws SQLException {
        String key = task.getResumeKeyColumn().trim();
        try (PreparedStatement boundary = connection.prepareStatement(
                EtlSqlSupport.resumeBoundary(select, key, family()),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            boundary.setMaxRows(2);
            Integer timeout = task.getTimeoutSeconds();
            if (timeout != null && timeout > 0) {
                boundary.setQueryTimeout(timeout);
            }
            bindResumeKey(boundary, checkpoint, config == null ? null : config.effectiveType());
            try (ResultSet rows = boundary.executeQuery()) {
                if (rows.next() && rows.next()) {
                    // Same shape as the runner's other resume-key guards: the key is appended
                    // after ':' so the run history can translate the main sentence.
                    throw new EtlProgramException("error.etl.resumeKey.duplicate:" + key);
                }
            }
        }
    }

    private String sourceQuery(String select, EtlTask task, EtlCheckpointValue checkpoint,
                               boolean resumable) {
        String sql = select;
        String key = task.getResumeKeyColumn() == null
                ? null : task.getResumeKeyColumn().trim();
        if (resumable) {
            // The newline keeps a trailing "--" comment in the customer's SQL from
            // swallowing the closing paren and the resume condition.
            String wrapped = "SELECT * FROM ("
                    + EtlSqlSupport.derivedTableSource(sql, family())
                    + "\n) " + EtlSqlSupport.SUBQUERY_ALIAS;
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
     * value buffers the whole result in the driver. MariaDB's own Connector/J rejects the
     * sentinel and streams on the normal value, so the product — not the family — decides.
     */
    private int fetchSize() {
        DatabaseType type = config == null ? null : config.effectiveType();
        return type != null && type.needsConnectorJStreamingSentinel() ? Integer.MIN_VALUE : FETCH_SIZE;
    }

    /**
     * The text a checkpoint stores for the resume key.
     *
     * <p>{@link #bindResumeKey} reads that text back with {@code Timestamp.valueOf},
     * {@code Date.valueOf} and similar parsers, so it has to be an ISO wall clock. Most
     * drivers already render temporal columns that way, but Oracle formats them with the
     * session's NLS settings ({@code 14-NOV-26 10.00.00.123456 AM} — and its default
     * {@code NLS_DATE_FORMAT} carries no time of day at all). Storing that text made every
     * resume of an interrupted Oracle extraction fail as "stored resume value ... cannot be
     * read back", so the run could never continue.
     *
     * <p>Text that already round-trips is kept verbatim: MySQL renders TIMESTAMP in the
     * server session zone, and re-rendering it through the JVM zone would move the resume
     * boundary whenever the two differ. Only text the binder cannot read is replaced by the
     * typed value rendered as a wall clock.
     */
    static String resumeKeyText(int jdbcType, Object value, String driverText) {
        if (driverText == null || parsesBack(jdbcType, driverText.trim())) {
            return driverText;
        }
        String canonical = wallClockText(jdbcType, value);
        return canonical == null ? driverText : canonical;
    }

    /**
     * Whether {@link #bindResumeKey} can read this text back for the given type.
     *
     * <p>Package-private so the runner can refuse a value it would otherwise checkpoint:
     * a stored value that cannot be bound poisons every later run with a PROGRAM failure.
     */
    static boolean parsesBack(int jdbcType, String text) {
        try {
            switch (jdbcType) {
                case Types.DATE:
                    Date.valueOf(text);
                    return true;
                case Types.TIME:
                    // Time.valueOf wraps rather than rejects out-of-domain hours
                    // ("838:59:59" -> "22:59:59"), which would move the resume boundary
                    // silently. Only a faithful round trip counts as readable.
                    Time time = Time.valueOf(text);
                    return TIME_TEXT.format(time.toLocalTime()).equals(text);
                case Types.TIMESTAMP:
                    return wallClock(text) != null;
                default:
                    return true;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The typed value as the ISO text {@link #bindResumeKey} expects, or null. */
    private static String wallClockText(int jdbcType, Object value) {
        switch (jdbcType) {
            case Types.DATE:
                if (value instanceof java.time.LocalDate date) {
                    return date.toString();
                }
                if (value instanceof Date date) {
                    return date.toLocalDate().toString();
                }
                if (value instanceof java.time.LocalDateTime wall) {
                    return wall.toLocalDate().toString();
                }
                return null;
            case Types.TIME:
                if (value instanceof java.time.LocalTime time) {
                    return Time.valueOf(time).toString();
                }
                if (value instanceof Time time) {
                    return time.toString();
                }
                return null;
            case Types.TIMESTAMP:
                if (value instanceof java.time.LocalDateTime wall) {
                    return JdbcRowMapper.wallClockToString(wall);
                }
                if (value instanceof Timestamp stamp) {
                    return JdbcRowMapper.wallClockToString(stamp.toLocalDateTime());
                }
                return null;
            default:
                return null;
        }
    }

    static void bindResumeKey(PreparedStatement statement, EtlCheckpointValue checkpoint,
                              DatabaseType sourceType) throws SQLException {
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
                    java.time.LocalDateTime wall = wallClock(value);
                    if (wall == null) {
                        statement.setTimestamp(1, Timestamp.valueOf(value));
                    } else {
                        // The stored text is the wall clock the source rendered; binding it
                        // as a plain Timestamp (or via setObject on Oracle's and PgJDBC's
                        // drivers) reads it in the JVM zone and moves historical DST-gap
                        // times (Asia/Shanghai before 1949) forward an hour.
                        JdbcRowMapper.bindWallClock(statement, 1, wall,
                                JdbcRowMapper.bindsZoneLessThroughUtcCalendar(sourceType));
                    }
                    break;
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    // PostgreSQL has no timestamptz > varchar operator, so the text the
                    // checkpoint stores (the driver's own rendering, offset included) is
                    // rejected by the continuation query. Bind the instant it carries
                    // instead. Oracle's zone-aware columns keep the text: the NLS format
                    // getString rendered is what its implicit VARCHAR2 conversion parses
                    // back, and a zone-less Timestamp parameter would be read in the
                    // session zone instead.
                    java.time.OffsetDateTime zoneAware = isPostgres(sourceType)
                            ? zoneAwareText(value) : null;
                    if (zoneAware != null) {
                        statement.setTimestamp(1, Timestamp.from(zoneAware.toInstant()));
                    } else {
                        statement.setString(1, value);
                    }
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

    private static boolean isPostgres(DatabaseType type) {
        return type != null
                && type.getFamily() == DatabaseType.DialectFamily.POSTGRES;
    }

    /**
     * The text as an offset-carrying instant, or null when it is not one. PostgreSQL renders
     * {@code timestamptz} as "yyyy-MM-dd HH:mm:ss[.f…]±HH[:mm]", not as ISO text.
     */
    private static java.time.OffsetDateTime zoneAwareText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        String iso = t.indexOf(' ') >= 0 ? t.replace(' ', 'T') : t;
        try {
            return java.time.OffsetDateTime.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.Instant.parse(iso).atOffset(java.time.ZoneOffset.UTC);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** The text as a zone-less wall clock, or null when it is not "yyyy-MM-dd HH:mm:ss[.f…]". */
    private static java.time.LocalDateTime wallClock(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() < 19 || (t.charAt(10) != ' ' && t.charAt(10) != 'T')) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(t.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
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
