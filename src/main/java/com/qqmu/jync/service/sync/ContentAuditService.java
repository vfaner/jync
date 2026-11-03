package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.dto.TableContentAudit.Status;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.util.JdbcUtil;
import com.qqmu.jync.util.TargetTableVerifier;

/**
 * Value-level companion to the row-count audit.
 *
 * <p>The row-count audit answers "did every row the cursor claims to have delivered arrive?"
 * It cannot see a target row whose count is right but whose values are stale, truncated, or
 * converted differently — an upsert the target rejected piecewise, a column whose type was
 * narrowed on the target, a character-set conversion that mangled text. This audit samples a
 * handful of keys across the synced range, reads the same rows on both sides, and compares
 * the projected values.
 *
 * <p>It only reports. A repair would need to know which side is authoritative, and for a
 * target with external writers that is not a question this tool can answer. The one thing it
 * does guarantee is that a difference becomes visible in the change log and the drift alert
 * without flooding them: the same fingerprint for the same table is recorded once.
 */
@Service
public class ContentAuditService {

    private static final Logger log = LoggerFactory.getLogger(ContentAuditService.class);

    private static final int MIN_SAMPLE_SIZE = 2;
    private static final int MAX_SAMPLE_SIZE = 200;
    private static final int MAX_DETAIL_LENGTH = 200;

    private final DataSyncService dataSync;
    private final SyncStateWriter stateWriter;
    private final SyncProperties properties;

    /** projectId -> epoch millis of the last audit scheduled for it. */
    private final Map<Long, Long> lastAuditAt = new ConcurrentHashMap<>();
    /** "projectId:table" -> fingerprint of the mismatch last recorded for it. */
    private final Map<String, String> lastFingerprint = new ConcurrentHashMap<>();

    public ContentAuditService(DataSyncService dataSync, SyncStateWriter stateWriter,
            SyncProperties properties) {
        this.dataSync = dataSync;
        this.stateWriter = stateWriter;
        this.properties = properties;
    }

    /**
     * Scheduled entry point: runs at most one audit per project per configured interval, and
     * at most {@code content-audit-max-tables-per-cycle} tables per cycle.
     *
     * @return null when the interval or the cycle budget suppresses the audit this time
     */
    public TableContentAudit maybeAudit(Connection sourceConn, Connection targetConn,
            TableMeta table, CursorStrategy strategy, SyncProgress progress, SyncContext ctx,
            CycleBudget budget) {
        if (properties.getContentAuditIntervalMs() <= 0) {
            return null;
        }
        Long projectId = projectKey(ctx);
        long now = System.currentTimeMillis();
        Long previous = lastAuditAt.get(projectId);
        if (previous != null && now - previous < properties.getContentAuditIntervalMs()) {
            return null;
        }
        if (budget != null && !budget.tryAcquire()) {
            return null;
        }
        lastAuditAt.put(projectId, now);
        return auditNow(sourceConn, targetConn, table, strategy, progress, ctx);
    }

    /**
     * One audit, ignoring the interval and cycle budget. Eligibility (is this table something
     * the audit can compare at all?) still applies — the manual entry point is a way to run
     * now, not a way to make an incomparable table comparable.
     */
    public TableContentAudit auditNow(Connection sourceConn, Connection targetConn,
            TableMeta table, CursorStrategy strategy, SyncProgress progress, SyncContext ctx) {
        long started = System.currentTimeMillis();
        String tableName = table == null ? "?" : table.getName();

        String ineligible = ineligibleReason(table, strategy, progress, ctx);
        if (ineligible != null) {
            return TableContentAudit.skipped(tableName, ineligible, elapsed(started));
        }

        Object cursor = JdbcRowMapper.cursorFromString(progress.getLastSyncValue(),
                strategy.getJdbcType());
        try {
            return compare(sourceConn, targetConn, table, strategy, cursor, ctx, started);
        } catch (SQLException | RuntimeException e) {
            // An audit is a diagnostic. Its failure must never change the sync's outcome,
            // so it is reported in the result instead of thrown at the engine.
            log.warn("Content audit of {} failed: {}", tableName, e.getMessage());
            return TableContentAudit.error(tableName, truncate(e.getMessage()), elapsed(started));
        }
    }

    /**
     * Records a mismatch in the change log, once per fingerprint. A later audit that finds the
     * same table clean clears the fingerprint, so a drift that returns is reported again.
     */
    public void record(SyncContext ctx, TableContentAudit result) {
        if (result == null || result.table() == null) {
            return;
        }
        String key = projectKey(ctx) + ":" + result.table();
        if (result.status() != Status.MISMATCH) {
            lastFingerprint.remove(key);
            return;
        }
        String fingerprint = result.mismatched() + "/" + result.missingOnTarget() + "/"
                + String.join(",", result.mismatchColumns());
        if (fingerprint.equals(lastFingerprint.put(key, fingerprint))) {
            return;
        }
        StringBuilder details = new StringBuilder("Content audit: table ")
                .append(result.table()).append(": ")
                .append(result.sampled()).append(" sampled, ")
                .append(result.mismatched()).append(" row(s) differ");
        if (result.missingOnTarget() > 0) {
            details.append(", ").append(result.missingOnTarget()).append(" missing on target");
        }
        if (!result.mismatchColumns().isEmpty()) {
            details.append(" (columns: ")
                    .append(String.join(", ", result.mismatchColumns())).append(')');
        }
        stateWriter.recordLog(projectKey(ctx), ObjectType.TABLE, result.table(),
                ChangeType.INFO, details.toString(), true, result.elapsedMs(), 0);
    }

    /** Forgets the throttling and dedup state of a project that was stopped or deleted. */
    public void evictProject(Long projectId) {
        if (projectId == null) {
            return;
        }
        lastAuditAt.remove(projectId);
        String prefix = projectId + ":";
        lastFingerprint.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public CycleBudget newCycleBudget() {
        return new CycleBudget(properties.getContentAuditMaxTablesPerCycle());
    }

    /** Per-cycle cap shared by every table of one sync run. */
    public static final class CycleBudget {
        private final int max;
        private final AtomicInteger used = new AtomicInteger();

        CycleBudget(int max) {
            this.max = max;
        }

        /** 0 or less means "no cap". */
        boolean tryAcquire() {
            return max <= 0 || used.incrementAndGet() <= max;
        }
    }

    /** Why the audit cannot run for this table, or null when it can. */
    private String ineligibleReason(TableMeta table, CursorStrategy strategy,
            SyncProgress progress, SyncContext ctx) {
        if (ctx.getConfig() == null || !ctx.getConfig().isSyncData()) {
            return "not-sync-data";
        }
        if (strategy == null) {
            return "not-incremental";
        }
        if (strategy.isPseudoColumn()) {
            // ORA_ROWSCN has no counterpart on the target, so there is nothing to compare.
            return "pseudo-cursor";
        }
        if (!strategy.isIncremental()) {
            // FULL_COMPARE re-reads everything every cycle; NONE never syncs again.
            return "not-incremental";
        }
        if (table == null || table.getPrimaryKeys() == null
                || table.getPrimaryKeys().isEmpty()) {
            return "no-primary-key";
        }
        if (!Boolean.TRUE.equals(progress.getInitialLoadDone())) {
            return "initial-load-pending";
        }
        if (JdbcRowMapper.cursorFromString(progress.getLastSyncValue(),
                strategy.getJdbcType()) == null) {
            return "no-cursor";
        }
        // A cleansing rule on a key column changes the value used to locate the target row,
        // so the key sampled from the source may no longer name the same row on the target.
        ColumnCleanser cleanser = ColumnCleanser.forTable(ctx.getConfig(), table.getName());
        for (String keyColumn : table.getPrimaryKeys()) {
            for (String cleansed : cleanser.columns()) {
                if (cleansed.equalsIgnoreCase(keyColumn)) {
                    return "cleansed-key";
                }
            }
        }
        return null;
    }

    private TableContentAudit compare(Connection sourceConn, Connection targetConn,
            TableMeta table, CursorStrategy strategy, Object cursor, SyncContext ctx,
            long started) throws SQLException {
        String tableName = table.getName();

        Long estimate = dataSync.rowEstimate(sourceConn, table, ctx);
        long maxRows = properties.getContentAuditMaxRows();
        if (maxRows > 0 && estimate != null && estimate > maxRows) {
            return TableContentAudit.skipped(tableName, "too-large", elapsed(started));
        }
        if (dataSync.bookkeepingCountWouldScan(sourceConn, table, strategy, ctx,
                "content audit")) {
            return TableContentAudit.skipped(tableName, "unindexed-scan", elapsed(started));
        }

        String targetTable = ctx.getConfig().targetTableName(tableName);
        Map<String, String> targetNames = TargetTableVerifier.columnNamesByUpperName(targetConn,
                ctx.getTargetSchema(), targetTable, ctx.getTargetType());
        if (targetNames.isEmpty()) {
            return TableContentAudit.skipped(tableName, "target-missing", elapsed(started));
        }
        if (!TargetTableVerifier.missingColumns(targetConn, ctx.getTargetSchema(), targetTable,
                table, ctx.getTargetType()).isEmpty()) {
            return TableContentAudit.skipped(tableName, "target-missing-columns",
                    elapsed(started));
        }

        Set<String> keyColumns = new HashSet<>();
        for (String key : table.getPrimaryKeys()) {
            keyColumns.add(key.toUpperCase(Locale.ROOT));
        }
        List<String> nonKeyColumns = new ArrayList<>();
        for (ColumnMeta column : table.getColumns()) {
            if (!keyColumns.contains(column.getName().toUpperCase(Locale.ROOT))) {
                nonKeyColumns.add(column.getName());
            }
        }
        Set<String> excluded = nonComparable(sourceConn, targetConn, table, targetTable,
                nonKeyColumns, ctx);
        List<String> comparable = new ArrayList<>(table.getPrimaryKeys());
        for (String column : nonKeyColumns) {
            if (!excluded.contains(column.toUpperCase(Locale.ROOT))) {
                comparable.add(column);
            }
        }

        int sampleSize = Math.max(MIN_SAMPLE_SIZE,
                Math.min(MAX_SAMPLE_SIZE, properties.getContentAuditSampleSize()));
        Object bound = dataSync.auditedBound(targetConn, ctx, table, strategy, cursor);

        List<List<Object>> keys;
        try {
            keys = dataSync.sampleKeys(sourceConn, table, strategy, bound, ctx, sampleSize);
        } catch (SQLException e) {
            // Products without window functions (MySQL 5.7 and some embedded engines) cannot
            // split the key range. Degrading to ORDER BY ... LIMIT would sample the oldest
            // rows only, which is exactly where drift is least likely; skipping is honest.
            log.info("Content audit of {} skipped: source cannot sample keys ({})",
                    tableName, e.getMessage());
            return TableContentAudit.skipped(tableName, "sampling-unsupported",
                    elapsed(started));
        }
        if (keys == null || keys.isEmpty()) {
            return new TableContentAudit(tableName, Status.OK, 0, 0, 0, 0,
                    excluded.size(), List.of(), null, elapsed(started));
        }

        List<String> sourceSelect = comparable;
        List<String> targetSelect = new ArrayList<>(comparable.size());
        for (String column : comparable) {
            targetSelect.add(targetNames.getOrDefault(column.toUpperCase(Locale.ROOT), column));
        }
        int timeout = properties.getContentAuditTimeoutSeconds();
        Map<String, Map<String, Object>> sourceRows = fetchRows(sourceConn,
                ctx.getSourceDialect(), ctx.getSourceSchema(), tableName,
                table.getPrimaryKeys(), sourceSelect, keys, strategy, bound, timeout,
                ctx.effectiveSourceType());
        Map<String, Map<String, Object>> targetRows = fetchRows(targetConn,
                ctx.getTargetDialect(), ctx.getTargetSchema(), targetTable,
                table.getPrimaryKeys(), targetSelect, keys, strategy, bound, timeout,
                ctx.effectiveTargetType());

        ColumnCleanser cleanser = ColumnCleanser.forTable(ctx.getConfig(), tableName);
        int matched = 0;
        int mismatched = 0;
        int missing = 0;
        Set<String> mismatchColumns = new LinkedHashSet<>();
        for (List<Object> key : keys) {
            String normalized = DataSyncService.normalizedKey(key);
            Map<String, Object> sourceRow = sourceRows.get(normalized);
            if (sourceRow == null) {
                // Deleted between sampling and reading; the next cycle's cursor decides.
                continue;
            }
            Map<String, Object> targetRow = targetRows.get(normalized);
            if (targetRow == null) {
                missing++;
                mismatched++;
                continue;
            }
            Map<String, Object> cleansed = cleanser.apply(sourceRow);
            boolean differs = false;
            for (String column : comparable) {
                if (!canonicalValue(lookup(cleansed, column))
                        .equals(canonicalValue(lookup(targetRow, column)))) {
                    differs = true;
                    mismatchColumns.add(column);
                }
            }
            if (differs) {
                mismatched++;
            } else {
                matched++;
            }
        }

        Status status = mismatched == 0 ? Status.OK : Status.MISMATCH;
        return new TableContentAudit(tableName, status, keys.size(), matched, mismatched,
                missing, excluded.size(), List.copyOf(mismatchColumns), null,
                elapsed(started));
    }

    /**
     * Columns whose values cannot be compared across the two drivers: the target dictionary
     * says {@code =} is undefined (LOB, JSON, XML, geometry), or the source JDBC type is one
     * of the streaming/large family whose value is not worth materialising for a spot check.
     */
    private Set<String> nonComparable(Connection sourceConn, Connection targetConn,
            TableMeta table, String targetTable, List<String> candidates, SyncContext ctx)
            throws SQLException {
        Set<String> excluded = new HashSet<>();
        for (String column : TargetTableVerifier.uncomparableColumns(targetConn,
                ctx.getTargetSchema(), targetTable, ctx.getTargetType(), candidates)) {
            excluded.add(column.toUpperCase(Locale.ROOT));
        }
        for (ColumnMeta column : table.getColumns()) {
            if (isLargeType(column.getJdbcType())) {
                excluded.add(column.getName().toUpperCase(Locale.ROOT));
            }
        }
        return excluded;
    }

    private static boolean isLargeType(int jdbcType) {
        switch (jdbcType) {
            case Types.CLOB:
            case Types.NCLOB:
            case Types.BLOB:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.LONGVARBINARY:
            case Types.SQLXML:
            case Types.ARRAY:
            case Types.STRUCT:
            case Types.JAVA_OBJECT:
            case Types.OTHER:
                return true;
            default:
                return false;
        }
    }

    private Map<String, Map<String, Object>> fetchRows(Connection conn, SqlDialect dialect,
            String schema, String tableName, List<String> pkColumns, List<String> selectColumns,
            List<List<Object>> keys, CursorStrategy strategy, Object bound, int timeoutSeconds,
            com.qqmu.jync.model.DatabaseType type) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.keysSelectSql(dialect, schema, tableName, pkColumns,
                selectColumns, keys, strategy, bound, params);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            JdbcUtil.applyQueryTimeout(st, timeoutSeconds);
            for (int i = 0; i < params.size(); i++) {
                st.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = st.executeQuery()) {
                List<String> names = JdbcRowMapper.columnNames(rs.getMetaData());
                List<Integer> types = JdbcRowMapper.columnTypes(rs.getMetaData());
                List<String> typeNames = JdbcRowMapper.columnTypeNames(rs.getMetaData());
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    JdbcRowMapper.readRowInto(rs, names, types, typeNames, type, row);
                    List<Object> key = new ArrayList<>(pkColumns.size());
                    for (String pk : pkColumns) {
                        key.add(lookup(row, pk));
                    }
                    rows.put(DataSyncService.normalizedKey(key), row);
                }
            }
        }
        return rows;
    }

    private static Object lookup(Map<String, Object> row, String column) {
        if (row == null || column == null) {
            return null;
        }
        Object direct = row.get(column);
        if (direct != null || row.containsKey(column)) {
            return direct;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * One value as a comparable string, so the same value through two drivers compares equal:
     * a Boolean and its 1/0, a {@code BigDecimal 5.00} and a {@code Long 5}, a
     * {@code LocalDateTime} and the {@code Timestamp} another driver returns for it.
     */
    public static String canonicalValue(Object value) {
        if (value instanceof Boolean flag) {
            return DataSyncService.normalizedKey(List.of(flag ? 1 : 0));
        }
        if (value instanceof String text) {
            Timestamp stamp = parseWallClock(text);
            if (stamp != null) {
                return DataSyncService.normalizedKey(Collections.singletonList(stamp));
            }
        }
        return DataSyncService.normalizedKey(Collections.singletonList(value));
    }

    /**
     * The {@code yyyy-MM-dd HH:mm:ss[.fffffffff]} wall clock as a {@link Timestamp}, or null
     * for any other text.
     *
     * <p>MySQL's driver hands DATETIME over as this literal (see
     * {@code JdbcRowMapper.readValue}: the value must survive the target bind verbatim), while
     * the same instant on a TIMESTAMP column arrives as a {@code Timestamp}. Comparing the two
     * as strings marked every row of a MySQL-protocol sync as differing.
     */
    private static Timestamp parseWallClock(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 19
                || (trimmed.charAt(10) != ' ' && trimmed.charAt(10) != 'T')) {
            return null;
        }
        try {
            return Timestamp.valueOf(trimmed.replace('T', ' '));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() <= MAX_DETAIL_LENGTH
                ? message : message.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    private static Long projectKey(SyncContext ctx) {
        Long projectId = ctx.projectId();
        return projectId == null ? -1L : projectId;
    }
}
