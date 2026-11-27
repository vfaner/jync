package com.qqmu.jync.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.TablePreview;
import com.qqmu.jync.dto.TablePreviewCheck;
import com.qqmu.jync.dto.TableRowCount;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.JdbcUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only preview of a synced table on the source or the target side.
 *
 * <p>This is a look, not a database client: the SQL is always a fixed
 * {@code SELECT * FROM <schema>.<table>} over a name that came from the project's own
 * metadata, the row count is capped, and nothing here can write. It exists so an operator
 * can confirm what actually landed on the target without opening a separate JDBC tool.
 */
@Service
@Slf4j
public class TablePreviewService {

    /** Rows shown per preview. The modal states the cap; one extra row is read to detect it. */
    public static final int ROW_LIMIT = 100;

    /** Keeps a runaway preview from occupying a request thread for the sync-wide timeout. */
    private static final int PREVIEW_TIMEOUT_SECONDS = 30;

    /** A row-count probe is user-triggered and scans the whole table; bound it. */
    private static final int ROW_COUNT_TIMEOUT_SECONDS = 15;

    /** Long text and binary cells are cut for transport; the modal is a glance, not an export. */
    private static final int CELL_MAX_LENGTH = 500;

    public enum Side { SOURCE, TARGET }

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final SqlDialectFactory dialectFactory;
    private final ObjectMapper objectMapper;

    public TablePreviewService(ProjectRepository projectRepository,
                               DatabaseConfigRepository databaseConfigRepository,
                               DataSourceManager dataSourceManager,
                               MetadataReaderFactory readerFactory,
                               SqlDialectFactory dialectFactory,
                               ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.dialectFactory = dialectFactory;
        this.objectMapper = objectMapper;
    }

    public TablePreview preview(Long projectId, String table, Side side) {
        long started = System.currentTimeMillis();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        DatabaseConfig config = sideConfig(project, side);

        // The declared family, not the bare type: a CUSTOM connection carries GENERIC and
        // would otherwise preview through the generic reader and the generic dialect.
        MetadataReader reader = readerFactory.forType(config.getType(), config.resolvedFamily());
        SqlDialect dialect = dialectFactory.forType(config.getType(), null, config.resolvedFamily());
        try (Connection conn = dataSourceManager.getConnection(config)) {
            String schema = resolveSchema(config, reader, conn);
            String requested = side == Side.TARGET
                    ? parseConfig(project).targetTableName(table) : table;
            String actualTable = resolveTableName(conn, reader, schema, requested);
            List<String> primaryKeys = primaryKeys(conn, reader, schema, actualTable);
            return read(conn, dialect, schema, actualTable, primaryKeys, config.getName(),
                    side, started, config.effectiveType());
        } catch (SQLException e) {
            // Keep driver detail server-side; the browser only resolves the key.
            log.debug("Cannot preview '{}' on the {} side of project '{}': {}",
                    table, side, project.getName(), e.toString());
            throw new IllegalStateException("error.preview.read.failed", e);
        }
    }

    /**
     * Quick content check for the preview modal: compare the first ten displayed rows and
     * the two column counts. It deliberately reuses the preview path, so the comparison
     * matches exactly what the operator sees and never opens a second query shape.
     */
    public TablePreviewCheck checkPreview(Long projectId, String tableName) {
        return checkPreview(projectId, tableName, PREVIEW_CHECK_ROWS);
    }

    /** Same check with a caller-selected row budget, clamped to the preview page size. */
    public TablePreviewCheck checkPreview(Long projectId, String tableName, int rows) {
        long started = System.currentTimeMillis();
        String table = tableName == null ? "?" : tableName.trim();
        int rowBudget = Math.max(1, Math.min(ROW_LIMIT, rows));
        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            DatabaseConfig sourceConfig = sideConfig(project, Side.SOURCE);
            DatabaseConfig targetConfig = sideConfig(project, Side.TARGET);
            MetadataReader sourceReader = readerFactory.forType(sourceConfig.getType(),
                    sourceConfig.resolvedFamily());
            MetadataReader targetReader = readerFactory.forType(targetConfig.getType(),
                    targetConfig.resolvedFamily());
            SqlDialect sourceDialect = dialectFactory.forType(sourceConfig.getType(), null,
                    sourceConfig.resolvedFamily());
            SqlDialect targetDialect = dialectFactory.forType(targetConfig.getType(), null,
                    targetConfig.resolvedFamily());
            SyncConfig syncConfig = parseConfig(project);
            try (Connection sourceConn = dataSourceManager.getConnection(sourceConfig);
                 Connection targetConn = dataSourceManager.getConnection(targetConfig)) {
                String sourceSchema = resolveSchema(sourceConfig, sourceReader, sourceConn);
                String targetSchema = resolveSchema(targetConfig, targetReader, targetConn);
                String sourceTable = resolveTableName(sourceConn, sourceReader, sourceSchema, table);
                String targetTable = resolveTableName(targetConn, targetReader, targetSchema,
                        syncConfig.targetTableName(table));
                List<String> sourceKeys = primaryKeys(sourceConn, sourceReader, sourceSchema,
                        sourceTable);
                List<String> targetKeys = primaryKeys(targetConn, targetReader, targetSchema,
                        targetTable);
                TablePreview source = read(sourceConn, sourceDialect, sourceSchema, sourceTable,
                        sourceKeys, sourceConfig.getName(), Side.SOURCE, started,
                        sourceConfig.effectiveType());
                TablePreview target = read(targetConn, targetDialect, targetSchema, targetTable,
                        targetKeys, targetConfig.getName(), Side.TARGET, started,
                        targetConfig.effectiveType());
                boolean columnCountMatch = source.columns().size() == target.columns().size();
                if (!source.orderedByPrimaryKey() || !target.orderedByPrimaryKey()) {
                    // Without a primary key the two databases may return the same rows in
                    // different physical order. Comparing by position would then invent drift;
                    // the column counts are still meaningful.
                    return new TablePreviewCheck(table, columnCountMatch
                            ? TablePreviewCheck.Status.SKIPPED
                            : TablePreviewCheck.Status.MISMATCH,
                            source.columns().size(), target.columns().size(), columnCountMatch,
                            0, 0, 0, 0, List.of(),
                            columnCountMatch ? "unordered" : "column-count",
                            System.currentTimeMillis() - started);
                }
                return comparePreviews(table, source, target, rowBudget, started);
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("Preview check failed for '{}' of project {}: {}",
                    table, projectId, e.toString());
            String message = e.getMessage();
            // Known refusals are i18n keys the browser can resolve; a driver/config message
            // is server-side detail and must not be shown in the modal.
            String detail = message != null && message.startsWith("error.")
                    ? message : "error.preview.check.failed";
            return TablePreviewCheck.error(table, detail, System.currentTimeMillis() - started);
        }
    }

    /** Rows compared by the quick check; matches the preview's first page, not the full audit. */
    private static final int PREVIEW_CHECK_ROWS = 50;

    private TablePreviewCheck comparePreviews(String table, TablePreview source,
                                               TablePreview target, int rowBudget,
                                               long started) {
        int sourceColumns = source.columns().size();
        int targetColumns = target.columns().size();
        boolean columnCountMatch = sourceColumns == targetColumns;
        List<List<String>> sourceRows = source.rows();
        List<List<String>> targetRows = target.rows();
        int rowsToCompare = Math.min(rowBudget,
                Math.max(sourceRows.size(), targetRows.size()));
        // Match by column name, not position: a target created by an earlier version may
        // have the same columns in a different order, which is not a content difference.
        Map<String, Integer> targetIndexes = new HashMap<>();
        for (int i = 0; i < target.columns().size(); i++) {
            targetIndexes.put(target.columns().get(i).toUpperCase(Locale.ROOT), i);
        }
        int compared = 0;
        int matched = 0;
        int mismatched = 0;
        int missing = 0;
        Set<String> mismatchColumns = new LinkedHashSet<>();
        for (int row = 0; row < rowsToCompare; row++) {
            if (row >= sourceRows.size()) {
                // Extra target rows are a difference, not "the target is missing rows".
                mismatched++;
                continue;
            }
            if (row >= targetRows.size()) {
                missing++;
                mismatched++;
                continue;
            }
            compared++;
            List<String> left = sourceRows.get(row);
            List<String> right = targetRows.get(row);
            boolean differs = false;
            for (int column = 0; column < sourceColumns; column++) {
                Integer targetColumn = targetIndexes.get(
                        source.columns().get(column).toUpperCase(Locale.ROOT));
                if (targetColumn == null) {
                    continue;                           // covered by the column-count verdict
                }
                if (!Objects.equals(comparablePreviewValue(left.get(column)),
                        comparablePreviewValue(right.get(targetColumn)))) {
                    differs = true;
                    mismatchColumns.add(source.columns().get(column));
                }
            }
            if (differs) {
                mismatched++;
            } else {
                matched++;
            }
        }
        TablePreviewCheck.Status status;
        String detail = null;
        if (rowsToCompare == 0) {
            status = TablePreviewCheck.Status.SKIPPED;
            detail = "no-rows";
        } else if (!columnCountMatch) {
            status = TablePreviewCheck.Status.MISMATCH;
            detail = "column-count";
        } else if (mismatched > 0 || missing > 0) {
            status = TablePreviewCheck.Status.MISMATCH;
            detail = missing > 0 ? "missing-rows" : null;
        } else {
            status = TablePreviewCheck.Status.OK;
        }
        return new TablePreviewCheck(table, status, sourceColumns, targetColumns,
                columnCountMatch, rowsToCompare, matched, mismatched, missing,
                List.copyOf(mismatchColumns), detail, System.currentTimeMillis() - started);
    }

    /**
     * The two drivers spell the same value differently: Oracle renders TIMESTAMP fractions
     * as ".0" where MySQL omits them, and a DATE can come back as midnight. A quick check
     * should compare the value, not the driver's formatting.
     */
    private static String comparablePreviewValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.stripTrailing();
        // Only normalise values that actually look numeric or temporal. Applying the
        // ".0"/midnight rewrite to arbitrary text would hide a real difference such as
        // "release-1.0" vs "release-1".
        if (normalized.matches("[-+]?\\d+(\\.\\d+)?")) {
            return normalized.replaceFirst("\\.0+$", "");
        }
        if (normalized.matches("\\d{4}-\\d{2}-\\d{2}"
                + "([ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?)?")) {
            normalized = normalized.replaceFirst("\\.0+$", "");
            normalized = normalized.replaceFirst(" 00:00:00$", "");
        }
        return normalized;
    }

    /**
     * Exact row counts for one table, one COUNT(*) per side.
     *
     * <p>Deliberately not batched: the caller runs tables sequentially so a large schema
     * cannot turn one click into dozens of concurrent full scans. A failing side is
     * reported in place instead of failing the whole comparison.
     */
    public TableRowCount rowCount(Long projectId, String table) {
        return rowCount(projectId, table, true);
    }

    /**
     * Row counts for one table. {@code exact=false} asks the product for catalog statistics
     * (InnoDB {@code TABLE_ROWS}, PostgreSQL {@code reltuples}, Oracle {@code NUM_ROWS},
     * ...) and marks both sides approximate. Readers without a statistics source use their
     * own short metadata-bound fallback; this path never applies the row-count query's
     * 15-second full-scan budget. The page uses it as the default because a
     * five-hundred-million-row table cannot be counted inside a browser request.
     */
    public TableRowCount rowCount(Long projectId, String table, boolean exact) {
        return rowCount(projectId, table, exact, ROW_COUNT_TIMEOUT_SECONDS);
    }

    /**
     * Same as {@link #rowCount(Long, String, boolean)}, with an explicit total timeout.
     * The budget is shared by both sides; the background exact-count job passes the
     * administrator-configured total, so one slow side cannot silently double the wait.
     */
    public TableRowCount rowCount(Long projectId, String table, boolean exact,
                                  int timeoutSeconds) {
        return rowCount(projectId, table, exact, timeoutSeconds, new RowCountControl());
    }

    /** As above, with a control that lets a cancellation job stop the live statements. */
    public TableRowCount rowCount(Long projectId, String table, boolean exact,
                                  int timeoutSeconds, RowCountControl control) {
        long started = System.currentTimeMillis();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        RowCountControl handle = control == null ? new RowCountControl() : control;
        int timeout = Math.max(1, timeoutSeconds);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        return new TableRowCount(table,
                countSide(project, table, Side.SOURCE, exact, deadline, handle),
                countSide(project, table, Side.TARGET, exact, deadline, handle),
                System.currentTimeMillis() - started);
    }

    private TableRowCount.SideCount countSide(Project project, String table, Side side,
                                              boolean exact, long deadlineNanos,
                                              RowCountControl control) {
        if (control.isCancelled()) {
            return failed(TableRowCount.Status.ERROR, System.currentTimeMillis());
        }
        long started = System.currentTimeMillis();
        Long databaseId = side == Side.SOURCE ? project.getSourceDbId() : project.getTargetDbId();
        if (databaseId == null) {
            return failed(TableRowCount.Status.ERROR, started);
        }
        DatabaseConfig config = databaseConfigRepository.findById(databaseId).orElse(null);
        if (config == null) {
            return failed(TableRowCount.Status.ERROR, started);
        }
        MetadataReader reader = readerFactory.forType(config.getType(), config.resolvedFamily());
        SqlDialect dialect = dialectFactory.forType(config.getType(), null, config.resolvedFamily());
        try (Connection conn = dataSourceManager.getConnection(config)) {
            if (control.isCancelled()) {
                return failed(TableRowCount.Status.ERROR, started);
            }
            String schema = resolveSchema(config, reader, conn);
            String requested = side == Side.TARGET
                    ? parseConfig(project).targetTableName(table) : table;
            String actualTable = resolveTableName(conn, reader, schema, requested);
            if (!exact) {
                long count = reader.estimateRowCount(conn, schema, actualTable);
                return new TableRowCount.SideCount(TableRowCount.Status.OK, count,
                        System.currentTimeMillis() - started, true);
            }
            long remainingSeconds = remainingSeconds(deadlineNanos);
            if (remainingSeconds <= 0) {
                return failed(TableRowCount.Status.TIMEOUT, started);
            }
            String sql = "SELECT COUNT(*) FROM " + dialect.qualify(schema, actualTable);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                control.register(ps);
                try {
                    if (control.isCancelled()) {
                        return failed(TableRowCount.Status.ERROR, started);
                    }
                    JdbcUtil.applyQueryTimeout(ps, (int) Math.min(Integer.MAX_VALUE,
                            remainingSeconds));
                    try (ResultSet rs = ps.executeQuery()) {
                        long count = rs.next() ? rs.getLong(1) : 0L;
                        return new TableRowCount.SideCount(TableRowCount.Status.OK, count,
                                System.currentTimeMillis() - started, false);
                    }
                } finally {
                    control.unregister(ps);
                }
            }
        } catch (SQLException e) {
            if (isTimeout(e)) {
                return failed(TableRowCount.Status.TIMEOUT, started);
            }
            // Driver detail stays server-side; the browser only needs the status.
            log.debug("Cannot count '{}' on the {} side of project '{}': {}",
                    table, side, project.getName(), e.toString());
            return failed(TableRowCount.Status.ERROR, started);
        } catch (IllegalStateException e) {
            TableRowCount.Status status = "error.preview.table.unknown".equals(e.getMessage())
                    ? TableRowCount.Status.MISSING_TABLE : TableRowCount.Status.ERROR;
            return failed(status, started);
        }
    }

    private long remainingSeconds(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining));
    }

    private TableRowCount.SideCount failed(TableRowCount.Status status, long started) {
        return new TableRowCount.SideCount(status, null, System.currentTimeMillis() - started);
    }

    /**
     * Drivers do not agree on the exception type for a cancelled statement: JDBC defines
     * {@link SQLTimeoutException}, PostgreSQL reports SQLState 57014, and MySQL/H2 use HYT00.
     */
    private boolean isTimeout(SQLException e) {
        if (e instanceof SQLTimeoutException) {
            return true;
        }
        String state = e.getSQLState();
        return "HYT00".equals(state) || "57014".equals(state);
    }

    private DatabaseConfig sideConfig(Project project, Side side) {
        Long databaseId = side == Side.SOURCE ? project.getSourceDbId() : project.getTargetDbId();
        if (databaseId == null) {
            throw new IllegalStateException(side == Side.SOURCE
                    ? "error.project.source.required" : "error.project.target.required");
        }
        return databaseConfigRepository.findById(databaseId)
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));
    }

    private TablePreview read(Connection conn, SqlDialect dialect, String schema, String table,
                              List<String> primaryKeys, String connectionName, Side side,
                              long started, DatabaseType sourceType)
            throws SQLException {
        // Both sides must show the same rows in the same order. Without an explicit ORDER BY,
        // MySQL often returns primary-key order through its clustered index while Oracle
        // returns whatever the scan finds, so the two previews look unrelated even when the
        // data matches. Ordering by the primary key is portable and index-backed.
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(dialect.qualify(schema, table));
        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            sql.append(" ORDER BY ");
            for (int i = 0; i < primaryKeys.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(dialect.quoteIdentifier(primaryKeys.get(i)));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            // LIMIT is not portable across every supported product; setMaxRows is.
            ps.setMaxRows(ROW_LIMIT + 1);
            JdbcUtil.applyQueryTimeout(ps, PREVIEW_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>(meta.getColumnCount());
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == ROW_LIMIT) {
                        truncated = true;
                        break;
                    }
                    rows.add(readRow(rs, meta, sourceType));
                }
                // Measured here, after the rows are read: the modal shows this as how long
                // the preview took, and computing it before the SELECT reported ~0 ms.
                return new TablePreview(side.name().toLowerCase(java.util.Locale.ROOT),
                        connectionName, schema, table, columns, rows, truncated,
                        System.currentTimeMillis() - started,
                        primaryKeys != null && !primaryKeys.isEmpty());
            }
        }
    }

    /**
     * Primary-key columns used to order both previews. A view has none, and a reader that
     * cannot answer falls back to the database's natural order instead of failing the preview.
     */
    private List<String> primaryKeys(Connection conn, MetadataReader reader, String schema,
                                     String table) {
        try {
            TableMeta meta = reader.readTable(conn, schema, table);
            return meta == null || meta.getPrimaryKeys() == null
                    ? List.of() : meta.getPrimaryKeys();
        } catch (SQLException | RuntimeException e) {
            log.debug("Cannot read primary key for preview of '{}': {}", table, e.toString());
            return List.of();
        }
    }

    private List<String> readRow(ResultSet rs, ResultSetMetaData meta, DatabaseType sourceType)
            throws SQLException {
        int columnCount = meta.getColumnCount();
        List<String> row = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            // TDengine's driver reports VARCHAR and BINARY identically and answers getObject
            // with byte[], so a text cell would show as "<binary N bytes>". Text columns are
            // read through getString; every other source keeps getObject.
            int colType = meta.getColumnType(i);
            Object value;
            if (sourceType == DatabaseType.TDENGINE && colType == java.sql.Types.VARCHAR) {
                value = rs.getString(i);
            } else if (sourceType != null
                    && sourceType.getFamily() == DatabaseType.DialectFamily.MYSQL
                    && (colType == java.sql.Types.TIMESTAMP
                        || colType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE)) {
                // 与同步读取一致：MySQL TIMESTAMP 按墙钟读，避免 getTimestamp 在
                // serverTimezone=UTC 下按连接时区重解而偏移（预览曾显示 05:06、库存 21:06）。
                value = rs.getString(i);
            } else {
                value = rs.getObject(i);
            }
            // Connector/J with tinyInt1isBit (the default) reports MySQL TINYINT(1) as BIT
            // and answers getObject with Boolean, so a status cell holding 5 renders as
            // "true" — the modal would hide whether the sync wrote the right value at all.
            // Re-read it numerically, exactly like JdbcRowMapper does for the sync paths.
            // DatabaseType#tinyIntOneIsNumeric also covers the MySQL-protocol presets
            // whose driver reports the column as BOOLEAN (MariaDB) and Doris.
            if (value instanceof Boolean && sourceType != null
                    && sourceType.tinyIntOneIsNumeric()) {
                value = rs.getInt(i);
            }
            row.add(format(value));
        }
        return row;
    }

    private String format(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return binaryPlaceholder(bytes.length);
        }
        // H2, Oracle and others hand BLOB/CLOB back as locator objects, whose toString is
        // an internal descriptor ("blob0: X'…'") — useful to nobody and not the data.
        if (value instanceof java.sql.Blob blob) {
            return binaryPlaceholder((int) Math.min(blob.length(), Integer.MAX_VALUE));
        }
        if (value instanceof java.sql.Clob clob) {
            long length = clob.length();
            return truncate(clob.getSubString(1, (int) Math.min(length, CELL_MAX_LENGTH + 1L)));
        }
        return truncate(String.valueOf(value));
    }

    private String truncate(String text) {
        return text.length() > CELL_MAX_LENGTH
                ? text.substring(0, CELL_MAX_LENGTH) + "…" : text;
    }

    private String binaryPlaceholder(int length) {
        return "<binary " + length + " bytes>";
    }

    /** A corrupt config means defaults, never a dead page — same rule as the sync cycle. */
    private SyncConfig parseConfig(Project project) {
        return SyncContextFactory.parseConfig(project, objectMapper);
    }

    /**
     * Maps the requested name onto the spelling the database actually uses.
     *
     * <p>The request carries the name from the progress row, which may differ in case from
     * the dictionary (H2 upper-cases unquoted names, Oracle folds them, PostgreSQL does not).
     * Quoting a non-matching spelling would look for a case-sensitive twin that does not
     * exist — and, more importantly, only a name from this list is ever put into the SELECT.
     */
    private String resolveTableName(Connection conn, MetadataReader reader, String schema,
                                    String requested) throws SQLException {
        List<String> names = new ArrayList<>(reader.listTableNames(conn, schema));
        names.addAll(reader.listViewNames(conn, schema));
        for (String name : names) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        throw new IllegalStateException("error.preview.table.unknown");
    }

    /** Uses the configured schema, or asks the database what the connection's default is. */
    private String resolveSchema(DatabaseConfig config, MetadataReader reader, Connection conn)
            throws SQLException {
        if (config.getSchemaName() != null && !config.getSchemaName().isBlank()) {
            String schema = config.getSchemaName().trim();
            // Oracle- and DB2-family dictionaries fold unquoted identifiers to upper case;
            // quoting the user's lower-case spelling would target a case-sensitive twin that
            // does not exist.
            DatabaseType.DialectFamily family = config.resolvedFamily();
            return family == DatabaseType.DialectFamily.ORACLE
                    || family == DatabaseType.DialectFamily.DB2
                    ? schema.toUpperCase(java.util.Locale.ROOT) : schema;
        }
        return reader.resolveDefaultSchema(conn);
    }
}
