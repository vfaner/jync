package com.synctool.service.sync;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.synctool.config.SyncProperties;
import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.model.SyncProgress;
import com.synctool.service.converter.GenericSqlDialect;
import com.synctool.service.converter.SqlDialect;
import com.synctool.service.monitor.CursorStrategy;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Copies row changes from source to target.
 *
 * <h2>Why concurrent source writes cannot lose or duplicate data</h2>
 *
 * The sync reads a bounded window {@code (lastCursor, currentHighWatermark]} and only
 * advances the stored cursor after the target has committed the rows in that window. Three
 * things make that safe while the source is being written to:
 *
 * <ol>
 *   <li><b>The window is closed at read time.</b> The upper bound is captured from the source
 *       before reading and every query is bounded by it, so rows written during the read
 *       simply fall outside this window and are picked up next cycle. Without the upper
 *       bound, a long-running read could advance the cursor past rows it never actually saw.
 *   <li><b>The cursor advances only after a successful commit.</b> A crash between the target
 *       commit and the cursor update replays the window; a crash before the commit rolls the
 *       batch back. Either way no row is skipped.
 *   <li><b>Replays are harmless.</b> Every write is an upsert keyed on the primary key, so
 *       applying the same row twice converges to the same state. This is what turns
 *       at-least-once delivery into effectively-once results.
 * </ol>
 *
 * <p>The remaining hazard is a source transaction that assigns its timestamp before our read
 * but commits after it, which would place a committed row below an already-advanced
 * watermark. {@link SyncProperties#getSafetyLagMs()} rewinds the persisted watermark by a
 * small margin so those rows are re-read on the following cycle.
 */
@Service
@Slf4j
public class DataSyncService {

    private final SyncProperties properties;

    public DataSyncService(SyncProperties properties) {
        this.properties = properties;
    }

    /** Outcome of synchronizing one table. */
    @Getter
    public static class TableSyncResult {
        /**
         * Rows read from the source and handed to the target. For a full-compare table this is
         * the whole table on every cycle, so it measures work done, not change delivered.
         */
        private int rowsWritten;
        /** Rows the target inserted, where the target reports that distinctly. */
        private int rowsInserted;
        /** Rows the target updated, where the target reports that distinctly. */
        private int rowsUpdated;
        /**
         * Rows the target changed without saying whether it inserted or updated them. Most
         * targets cannot tell the two apart in an upsert's update count; those rows land here
         * and get attributed by sync phase instead of by what actually happened.
         */
        private int rowsChangedUnclassified;
        private int rowsDeleted;
        private boolean skipped;
        private String skipReason;
        private String error;
        /** The cursor value to persist, or null to leave the cursor untouched. */
        private String newCursorValue;
        private boolean initialLoad;

        /**
         * Total rows the target actually inserted or updated. This is the number worth showing a
         * user: an upsert of a row that already holds identical values counts for nothing, so a
         * full-compare cycle that found nothing new lands at zero rather than at the table's
         * row count.
         */
        public int getRowsChanged() {
            return rowsInserted + rowsUpdated + rowsChangedUnclassified;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * Row counts for one copy pass.
     *
     * <p>{@code read} and the change counts diverge whenever a write is a no-op — the same row
     * upserted twice is read twice but changed once. Keeping them apart is what lets the audit
     * log report a real change count instead of the size of the window we scanned.
     */
    private static final class WriteStats {
        private int read;
        private int inserted;
        private int updated;
        private int unclassified;
    }

    private static void applyStats(TableSyncResult result, WriteStats stats) {
        result.rowsInserted = stats.inserted;
        result.rowsUpdated = stats.updated;
        result.rowsChangedUnclassified = stats.unclassified;
    }

    /**
     * Synchronizes one table's rows.
     *
     * <p>The caller supplies both connections and is responsible for the target transaction
     * boundary; this method sets no autocommit mode of its own beyond the batch commits it
     * performs, and returns the cursor value the caller must persist together with the
     * committed data.
     */
    public TableSyncResult syncTable(Connection sourceConn, Connection targetConn,
                                     TableMeta table, CursorStrategy strategy,
                                     SyncProgress progress, SyncContext ctx) {
        TableSyncResult result = new TableSyncResult();
        String targetTable = ctx.getConfig().targetTableName(table.getName());

        if (strategy.getKind() == CursorStrategy.Kind.NONE) {
            boolean loaded = progress.getInitialLoadDone() != null && progress.getInitialLoadDone();
            if (loaded) {
                result.skipped = true;
                result.skipReason = strategy.getRationale();
                return result;
            }
            // Even without an incremental strategy the table deserves one full load, so the
            // target is at least seeded with current contents.
            log.info("Table {} has no incremental strategy; performing one-time full load",
                    table.getName());
        }

        try {
            boolean isInitialLoad = progress.getInitialLoadDone() == null
                    || !progress.getInitialLoadDone()
                    || progress.getLastSyncValue() == null;

            if (isInitialLoad) {
                result.initialLoad = true;
                return fullLoad(sourceConn, targetConn, table, targetTable, strategy, ctx, result);
            }
            if (strategy.getKind() == CursorStrategy.Kind.FULL_COMPARE) {
                return fullLoad(sourceConn, targetConn, table, targetTable, strategy, ctx, result);
            }
            return incrementalLoad(sourceConn, targetConn, table, targetTable, strategy,
                    progress, ctx, result);
        } catch (SQLException e) {
            log.error("Data sync failed for table {}: {}", table.getName(), e.getMessage());
            result.error = e.getMessage();
            // Deliberately leaves newCursorValue null so the window is retried next cycle.
            return result;
        }
    }

    /**
     * Reads and upserts the entire table, in pages, then records the current high-watermark
     * so subsequent runs can go incremental.
     */
    private TableSyncResult fullLoad(Connection sourceConn, Connection targetConn,
                                     TableMeta table, String targetTable,
                                     CursorStrategy strategy,
                                     SyncContext ctx, TableSyncResult result) throws SQLException {
        // Capture the watermark BEFORE reading, so rows committed during the load are not
        // silently covered by a watermark taken afterwards.
        String watermark = strategy.isIncremental()
                ? readHighWatermark(sourceConn, table, strategy, ctx)
                : null;

        SqlDialect sourceDialect = ctx.getSourceDialect();
        String selectSql = "SELECT * FROM "
                + sourceDialect.qualify(ctx.getSourceSchema(), table.getName());
        // Bound the read by the same watermark, so the load and the cursor agree exactly.
        if (watermark != null) {
            selectSql += " WHERE " + sourceDialect.quoteIdentifier(strategy.getColumn()) + " <= ?";
        }

        log.info("Full load of {} -> {}", table.getName(), targetTable);
        WriteStats stats = copyRows(sourceConn, targetConn, selectSql,
                watermark != null ? List.of(parseCursor(watermark, strategy)) : List.of(),
                table, targetTable, ctx);

        result.rowsWritten = stats.read;
        applyStats(result, stats);
        result.newCursorValue = watermark;

        // 非增量策略（full-compare）没有真正的游标，我们存一个"行数指纹"到 lastSyncValue，
        // 让下一轮知道初始加载已经做过了，否则 lastSyncValue == null 会让每一轮都当成首次加载。
        // 这一轮到底算不算"有变化"，由 rowsChanged 说话（驱动报回来的真实影响行数），
        // 而不是靠比对行数——行数不变但某个字段改了的情况，指纹是察觉不到的。
        if (strategy.getKind() == CursorStrategy.Kind.FULL_COMPARE) {
            result.newCursorValue = CursorStrategy.FULL_COMPARE_ROWCOUNT_PREFIX + stats.read;
        }
        return result;
    }

    /** Reads and upserts only rows inside {@code (lastCursor, watermark]}. */
    private TableSyncResult incrementalLoad(Connection sourceConn, Connection targetConn,
                                            TableMeta table, String targetTable,
                                            CursorStrategy strategy, SyncProgress progress,
                                            SyncContext ctx, TableSyncResult result)
            throws SQLException {
        String watermark = readHighWatermark(sourceConn, table, strategy, ctx);
        if (watermark == null) {
            // An empty source table: nothing to do, and nothing to advance.
            return result;
        }

        Object lastCursor = parseCursor(progress.getLastSyncValue(), strategy);
        Object upperBound = parseCursor(watermark, strategy);
        if (lastCursor != null && !isGreaterThan(upperBound, lastCursor)) {
            // The watermark has not moved past what we already synced.
            return result;
        }

        SqlDialect sourceDialect = ctx.getSourceDialect();
        String cursorCol = sourceDialect.quoteIdentifier(strategy.getColumn());
        // Half-open lower bound, closed upper bound: each row is delivered exactly once per
        // window, and the closed upper bound is what makes the window immune to concurrent
        // writes arriving mid-read.
        String selectSql = "SELECT * FROM "
                + sourceDialect.qualify(ctx.getSourceSchema(), table.getName())
                + " WHERE " + cursorCol + " > ? AND " + cursorCol + " <= ?"
                + " ORDER BY " + cursorCol;

        List<Object> params = new ArrayList<>();
        params.add(lastCursor);
        params.add(upperBound);

        WriteStats stats = copyRows(sourceConn, targetConn, selectSql, params, table, targetTable, ctx);
        result.rowsWritten = stats.read;
        applyStats(result, stats);

        if (stats.read > 0) {
            log.info("Synced {} row(s) ({} changed) for {} in window ({} .. {}]",
                    stats.read, result.getRowsChanged(), table.getName(),
                    progress.getLastSyncValue(), watermark);
        }

        // Rewind the persisted watermark slightly for timestamp cursors, so a source
        // transaction that committed out of clock order is re-read next cycle instead of
        // being skipped forever. Numeric cursors need no rewind: an identity value is
        // assigned and committed in order relative to the sequence.
        result.newCursorValue = strategy.getKind() == CursorStrategy.Kind.TIMESTAMP
                ? applySafetyLag(watermark)
                : watermark;
        return result;
    }

    /**
     * Subtracts the configured safety margin from a timestamp watermark.
     *
     * <p>The cost is that rows in the margin are re-delivered on the next cycle; the upsert
     * absorbs them. The benefit is that late-committing transactions are never lost.
     */
    private String applySafetyLag(String watermark) {
        long lag = properties.getSafetyLagMs();
        if (lag <= 0) {
            return watermark;
        }
        try {
            Instant instant = Instant.parse(watermark);
            return instant.minusMillis(lag).toString();
        } catch (Exception e) {
            // Not a timestamp after all; leave it alone rather than corrupt the cursor.
            return watermark;
        }
    }

    /**
     * Reads {@code MAX(cursorColumn)} from the source — the upper bound of this cycle's
     * window, captured before any data is read.
     */
    private String readHighWatermark(Connection sourceConn, TableMeta table,
                                     CursorStrategy strategy, SyncContext ctx) throws SQLException {
        if (!strategy.isIncremental()) {
            return null;
        }
        SqlDialect dialect = ctx.getSourceDialect();
        String sql = "SELECT MAX(" + dialect.quoteIdentifier(strategy.getColumn()) + ") FROM "
                + dialect.qualify(ctx.getSourceSchema(), table.getName());
        try (Statement st = sourceConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                Object value = strategy.getKind() == CursorStrategy.Kind.TIMESTAMP
                        ? rs.getTimestamp(1)
                        : rs.getObject(1);
                if (!rs.wasNull() && value != null) {
                    return JdbcRowMapper.cursorToString(value);
                }
            }
        }
        return null;
    }

    private Object parseCursor(String stored, CursorStrategy strategy) {
        if (stored == null) {
            return null;
        }
        return JdbcRowMapper.cursorFromString(stored, strategy.getJdbcType());
    }

    /**
     * Verdict of one row-count audit: how many rows each side holds at or below the cursor.
     *
     * <p>Both counts share the same bound, so they are directly comparable regardless of what
     * the source has written since.
     */
    @Getter
    public static final class RowCountAudit {
        private final long sourceRows;
        private final long targetRows;

        private RowCountAudit(long sourceRows, long targetRows) {
            this.sourceRows = sourceRows;
            this.targetRows = targetRows;
        }

        /** True when the target is missing rows the cursor claims were already delivered. */
        public boolean isTargetShort() {
            return targetRows < sourceRows;
        }
    }

    /**
     * Counts rows on both sides at or below the persisted cursor, to check that everything the
     * cursor claims to have delivered is really in the target.
     *
     * <p>The bound is what makes this trustworthy. Comparing whole tables would flag every
     * source row written since the last poll, which has legitimately not been synced yet; both
     * counts are therefore restricted to {@code cursorColumn <= lastSyncValue}, the exact range
     * the cursor asserts is complete.
     *
     * <p>The test is deliberately one-sided. A target holding extra rows is fine — rows deleted
     * at the source survive there when {@code syncDeletes} is off — so only a target that is
     * short is treated as broken. That also means the audit can miss a deficit that surplus rows
     * happen to mask, which is the right way to be wrong: acting on this triggers a full reload,
     * and a reload provoked by a miscount would be worse than a gap left for the next audit.
     *
     * @return null when there is nothing meaningful to compare — a non-incremental strategy, or
     *         a table whose cursor has not been established yet
     */
    public RowCountAudit auditSyncedRowCounts(Connection sourceConn, Connection targetConn,
                                              TableMeta table, CursorStrategy strategy,
                                              SyncProgress progress, SyncContext ctx)
            throws SQLException {
        if (!strategy.isIncremental() || strategy.getColumn() == null) {
            return null;
        }
        Object cursor = parseCursor(progress.getLastSyncValue(), strategy);
        if (cursor == null) {
            // Nothing claimed as delivered yet; the next cycle's initial load covers it.
            return null;
        }

        SqlDialect sourceDialect = ctx.getSourceDialect();
        long sourceRows = countAtOrBelow(sourceConn,
                sourceDialect.qualify(ctx.getSourceSchema(), table.getName()),
                sourceDialect.quoteIdentifier(strategy.getColumn()), cursor);

        SqlDialect targetDialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(table.getName());
        long targetRows = countAtOrBelow(targetConn,
                targetDialect.qualify(ctx.getTargetSchema(), targetTable),
                // Column names are copied verbatim from the source, so the cursor column is
                // spelled the same on both sides; only the quoting differs.
                targetDialect.quoteIdentifier(strategy.getColumn()), cursor);

        return new RowCountAudit(sourceRows, targetRows);
    }

    private long countAtOrBelow(Connection conn, String qualifiedTable, String quotedColumn,
                                Object cursor) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable + " WHERE " + quotedColumn + " <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, cursor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private boolean isGreaterThan(Object candidate, Object reference) {
        if (candidate == null) {
            return false;
        }
        if (reference == null) {
            return true;
        }
        if (candidate instanceof Timestamp && reference instanceof Timestamp) {
            return ((Timestamp) candidate).after((Timestamp) reference);
        }
        if (candidate instanceof Number && reference instanceof Number) {
            return new BigDecimal(candidate.toString())
                    .compareTo(new BigDecimal(reference.toString())) > 0;
        }
        return !candidate.equals(reference);
    }

    /**
     * Streams rows from the source query and upserts them into the target in batches.
     *
     * <p>Each batch is committed separately so a large table does not hold one enormous
     * transaction open, and so progress survives a mid-table failure. Partial progress is
     * safe precisely because the writes are idempotent and the cursor is not advanced until
     * the whole window succeeds.
     */
    private WriteStats copyRows(Connection sourceConn, Connection targetConn, String selectSql,
                                List<Object> params, TableMeta table, String targetTable,
                                SyncContext ctx) throws SQLException {
        SqlDialect targetDialect = ctx.getTargetDialect();
        List<String> pkColumns = table.getPrimaryKeys();

        boolean originalAutoCommit = targetConn.getAutoCommit();
        targetConn.setAutoCommit(false);

        WriteStats total = new WriteStats();
        try (PreparedStatement select = sourceConn.prepareStatement(selectSql)) {
            select.setFetchSize(properties.getFetchSize());
            for (int i = 0; i < params.size(); i++) {
                select.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = select.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = JdbcRowMapper.columnNames(md);
                List<Integer> jdbcTypes = JdbcRowMapper.columnTypes(md);
                Map<String, Integer> typeByColumn = new HashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    typeByColumn.put(columns.get(i), jdbcTypes.get(i));
                }

                String upsertSql = targetDialect.getUpsertSql(ctx.getTargetSchema(), targetTable,
                        columns, pkColumns);
                List<String> bindOrder = targetDialect.upsertBindOrder(columns, pkColumns);

                boolean emulateUpsert = pkColumns.isEmpty()
                        || (targetDialect instanceof GenericSqlDialect
                            && !((GenericSqlDialect) targetDialect).supportsNativeUpsert());

                if (pkColumns.isEmpty()) {
                    // Without a key there is no way to identify an existing row, so repeated
                    // delivery would duplicate data. Warn loudly; the user should add a key or
                    // accept insert-only semantics.
                    log.warn("Table {} has no primary key: writes cannot be made idempotent, so "
                            + "a retry may duplicate rows. Add a primary key for safe sync.",
                            table.getName());
                }

                total = emulateUpsert && !pkColumns.isEmpty()
                        ? writeWithEmulatedUpsert(targetConn, targetTable, columns, pkColumns,
                                typeByColumn, rs, jdbcTypes, ctx)
                        : writeWithNativeUpsert(targetConn, upsertSql, bindOrder, typeByColumn,
                                rs, columns, jdbcTypes, ctx);
            }
            targetConn.commit();
        } catch (SQLException e) {
            try {
                targetConn.rollback();
            } catch (SQLException rollbackError) {
                log.error("Rollback failed for {}: {}", targetTable, rollbackError.getMessage());
            }
            throw e;
        } finally {
            try {
                targetConn.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                log.debug("Could not restore autocommit: {}", e.getMessage());
            }
        }
        return total;
    }

    /** The normal path: one idempotent statement per row, executed in JDBC batches. */
    private WriteStats writeWithNativeUpsert(Connection targetConn, String upsertSql,
                                             List<String> bindOrder,
                                             Map<String, Integer> typeByColumn,
                                             ResultSet rs, List<String> columns,
                                             List<Integer> jdbcTypes,
                                             SyncContext ctx) throws SQLException {
        WriteStats stats = new WriteStats();
        int inBatch = 0;
        try (PreparedStatement upsert = targetConn.prepareStatement(upsertSql)) {
            while (rs.next()) {
                Map<String, Object> row = JdbcRowMapper.readRow(rs, columns, jdbcTypes);
                JdbcRowMapper.bind(upsert, row, bindOrder, typeByColumn);
                upsert.addBatch();
                inBatch++;
                stats.read++;

                if (inBatch >= ctx.getBatchSize()) {
                    executeBatch(upsert, targetConn, stats, ctx);
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                executeBatch(upsert, targetConn, stats, ctx);
            }
        }
        return stats;
    }

    /**
     * Fallback for targets without a native upsert: try UPDATE by key, and INSERT only when
     * the update matched nothing. Two statements per row, but still idempotent.
     */
    private WriteStats writeWithEmulatedUpsert(Connection targetConn, String targetTable,
                                               List<String> columns, List<String> pkColumns,
                                               Map<String, Integer> typeByColumn, ResultSet rs,
                                               List<Integer> jdbcTypes, SyncContext ctx)
            throws SQLException {
        SqlDialect dialect = ctx.getTargetDialect();
        GenericSqlDialect generic = dialect instanceof GenericSqlDialect
                ? (GenericSqlDialect) dialect : null;

        String updateSql = generic != null
                ? generic.getUpdateByPkSql(ctx.getTargetSchema(), targetTable, columns, pkColumns)
                : null;
        String insertSql = dialect.getUpsertSql(ctx.getTargetSchema(), targetTable, columns,
                List.of());

        List<String> updateBindOrder = new ArrayList<>();
        if (updateSql != null) {
            // SET clause values first, then the WHERE key values.
            for (String c : columns) {
                if (!containsIgnoreCase(pkColumns, c)) {
                    updateBindOrder.add(c);
                }
            }
            updateBindOrder.addAll(pkColumns);
        }

        WriteStats stats = new WriteStats();
        try (PreparedStatement insert = targetConn.prepareStatement(insertSql);
             PreparedStatement update = updateSql == null ? null
                     : targetConn.prepareStatement(updateSql)) {
            int sinceCommit = 0;
            while (rs.next()) {
                Map<String, Object> row = JdbcRowMapper.readRow(rs, columns, jdbcTypes);
                int updated = 0;
                if (update != null) {
                    JdbcRowMapper.bind(update, row, updateBindOrder, typeByColumn);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    JdbcRowMapper.bind(insert, row, columns, typeByColumn);
                    try {
                        // This path issues the INSERT and the UPDATE as separate statements, so
                        // it knows which one happened without the target having to say.
                        if (insert.executeUpdate() != 0) {
                            stats.inserted++;
                        }
                    } catch (SQLException e) {
                        // A concurrent writer may have inserted the same key between our
                        // UPDATE and INSERT. Retrying the update converges without failing.
                        if (isUniqueViolation(e) && update != null) {
                            JdbcRowMapper.bind(update, row, updateBindOrder, typeByColumn);
                            if (update.executeUpdate() != 0) {
                                stats.updated++;
                            }
                        } else {
                            throw e;
                        }
                    }
                } else {
                    stats.updated++;
                }
                stats.read++;
                if (++sinceCommit >= ctx.getBatchSize()) {
                    targetConn.commit();
                    sinceCommit = 0;
                }
            }
        }
        return stats;
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** SQLState 23xxx is the integrity-constraint class; 23505/23000 are unique violations. */
    private boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.toLowerCase().contains("duplicate")
                || message.toLowerCase().contains("unique constraint"));
    }

    /**
     * Executes a batch, commits it, and records what the target did with each row.
     *
     * <p>The per-statement update counts are what make the change count honest. On MySQL an
     * upsert of a row the target already holds verbatim reports zero affected rows, so
     * re-scanning an unchanged table sums to zero instead of to its row count. MySQL goes
     * further and distinguishes the two kinds of write — 1 for an insert, 2 for an update — which
     * is what lets the audit log label a row as inserted rather than guessing from the sync
     * phase. Both behaviours depend on {@code useAffectedRows} in
     * {@link com.synctool.model.DatabaseType#MYSQL}'s URL.
     *
     * <p>Targets that cannot make that distinction are handled by
     * {@link com.synctool.model.DatabaseType#upsertCountsDistinguishInsertFromUpdate()}: their
     * changed rows are recorded as unclassified rather than assigned a kind on a guess. Note
     * that PostgreSQL's {@code ON CONFLICT DO UPDATE} also rewrites a row whose values are
     * unchanged, so against such a target the change count is an overcount — never an
     * undercount.
     *
     * <p>A driver that rewrites the batch into one statement may answer
     * {@link Statement#SUCCESS_NO_INFO} instead of a number. The row is then counted as changed
     * but unclassified, since the alternative is to guess in the direction of losing changes.
     *
     * <p>{@link java.sql.BatchUpdateException} is inspected so a single bad row names itself
     * rather than failing the whole table anonymously.
     */
    private void executeBatch(PreparedStatement ps, Connection conn, WriteStats stats,
                              SyncContext ctx) throws SQLException {
        try {
            int[] counts = ps.executeBatch();
            conn.commit();
            boolean classify = ctx.getTargetType() != null
                    && ctx.getTargetType().upsertCountsDistinguishInsertFromUpdate();
            for (int count : counts) {
                if (count == 0) {
                    // The row was already present with these exact values: nothing moved.
                    continue;
                }
                if (!classify || count == Statement.SUCCESS_NO_INFO) {
                    stats.unclassified++;
                } else if (count == 1) {
                    stats.inserted++;
                } else {
                    // MySQL answers 2 for an update that changed something. The magnitude is a
                    // marker, not a row count, so it contributes one row either way.
                    stats.updated++;
                }
            }
        } catch (java.sql.BatchUpdateException e) {
            int[] counts = e.getUpdateCounts();
            int failedIndex = -1;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == Statement.EXECUTE_FAILED) {
                    failedIndex = i;
                    break;
                }
            }
            throw new SQLException("Batch write failed"
                    + (failedIndex >= 0 ? " at row " + (failedIndex + 1) + " of the batch" : "")
                    + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
        }
    }

    /**
     * Deletes target rows whose keys no longer exist in the source.
     *
     * <p>Only offered for tables small enough to enumerate keys on both sides, because
     * detecting deletions without a source-side audit trail requires comparing full key sets.
     */
    public int syncDeletions(Connection sourceConn, Connection targetConn, TableMeta table,
                             SyncContext ctx) throws SQLException {
        if (!table.hasPrimaryKey()) {
            log.debug("Cannot sync deletions for {}: no primary key", table.getName());
            return 0;
        }
        long rows = table.getApproximateRowCount() == null ? Long.MAX_VALUE
                : table.getApproximateRowCount();
        if (rows > properties.getFullCompareMaxRows()) {
            log.debug("Skipping deletion detection for {}: {} rows exceeds the comparison ceiling",
                    table.getName(), rows);
            return 0;
        }

        List<String> pk = table.getPrimaryKeys();
        SqlDialect sourceDialect = ctx.getSourceDialect();
        SqlDialect targetDialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(table.getName());

        java.util.Set<String> sourceKeys = new java.util.HashSet<>();
        String sourceSql = "SELECT " + pk.stream().map(sourceDialect::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b).orElseThrow()
                + " FROM " + sourceDialect.qualify(ctx.getSourceSchema(), table.getName());
        try (Statement st = sourceConn.createStatement();
             ResultSet rs = st.executeQuery(sourceSql)) {
            while (rs.next()) {
                sourceKeys.add(keyOf(rs, pk.size()));
            }
        }

        List<List<Object>> toDelete = new ArrayList<>();
        String targetSql = "SELECT " + pk.stream().map(targetDialect::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b).orElseThrow()
                + " FROM " + targetDialect.qualify(ctx.getTargetSchema(), targetTable);
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(targetSql)) {
            while (rs.next()) {
                if (!sourceKeys.contains(keyOf(rs, pk.size()))) {
                    List<Object> values = new ArrayList<>(pk.size());
                    for (int i = 1; i <= pk.size(); i++) {
                        values.add(rs.getObject(i));
                    }
                    toDelete.add(values);
                }
            }
        }

        if (toDelete.isEmpty()) {
            return 0;
        }

        boolean originalAutoCommit = targetConn.getAutoCommit();
        targetConn.setAutoCommit(false);
        int deleted = 0;
        String deleteSql = targetDialect.getDeleteByPkSql(ctx.getTargetSchema(), targetTable, pk);
        try (PreparedStatement ps = targetConn.prepareStatement(deleteSql)) {
            for (List<Object> keyValues : toDelete) {
                for (int i = 0; i < keyValues.size(); i++) {
                    ps.setObject(i + 1, keyValues.get(i));
                }
                // Keyed delete: repeating it is a no-op, so this stays idempotent.
                deleted += ps.executeUpdate();
            }
            targetConn.commit();
        } catch (SQLException e) {
            targetConn.rollback();
            throw e;
        } finally {
            targetConn.setAutoCommit(originalAutoCommit);
        }
        log.info("Deleted {} row(s) from {} that no longer exist in source", deleted, targetTable);
        return deleted;
    }

    /** Builds a comparable composite-key string from the leading columns of a row. */
    private String keyOf(ResultSet rs, int columnCount) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= columnCount; i++) {
            Object value = rs.getObject(i);
            // Length-prefix each part so ("a","bc") and ("ab","c") cannot collide.
            String part = value == null ? "" : String.valueOf(value);
            sb.append(part.length()).append(':').append(part).append('\u0001');
        }
        return sb.toString();
    }

    /** Empties the target table, used before a requested full reload. */
    public void truncateTarget(Connection targetConn, TableMeta table, SyncContext ctx) {
        String targetTable = ctx.getConfig().targetTableName(table.getName());
        DdlExecutor executor = new DdlExecutor(targetConn);
        try {
            executor.execute(ctx.getTargetDialect().getTruncateSql(ctx.getTargetSchema(),
                    targetTable), false);
            log.info("Truncated target table {} before initial load", targetTable);
        } catch (SQLException e) {
            // TRUNCATE may be refused for a referenced table; DELETE is the fallback.
            log.warn("TRUNCATE failed for {} ({}); falling back to DELETE", targetTable,
                    e.getMessage());
            executor.executeQuietly("DELETE FROM "
                    + ctx.getTargetDialect().qualify(ctx.getTargetSchema(), targetTable));
        }
    }

    /** Column metadata for the target, needed when binding nulls with explicit types. */
    Map<String, Integer> typeMapOf(TableMeta table) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ColumnMeta c : table.getColumns()) {
            map.put(c.getName(), c.getJdbcType());
        }
        return map;
    }
}
