package com.qqmu.jync.service.sync;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.lob.LobReadPolicy;
import com.qqmu.jync.service.lob.LobSpillSession;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.lob.LobValues;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.util.CollectionUtil;
import com.qqmu.jync.util.TargetTableVerifier;
import com.qqmu.jync.util.JdbcUtil;

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

    /**
     * Intra-table resume points of chunked full loads. Null only in hand-built instances
     * (unit tests); when null, initial loads keep using the single streamed read.
     */
    private final ChunkCheckpointRepository checkpoints;

    /**
     * Temp-file store for spilled LOB values. Built on first use so a hand-constructed
     * instance in a unit test never touches the filesystem; the directory is created only
     * when a value actually spills.
     */
    private volatile LobSpillStore lobSpillStore;

    public DataSyncService(SyncProperties properties) {
        this(properties, null);
    }

    @Autowired
    public DataSyncService(SyncProperties properties, ChunkCheckpointRepository checkpoints) {
        this.properties = properties;
        this.checkpoints = checkpoints;
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
         * Whether this table's full load is checkpointed and therefore resumable: true when
         * the keyset-chunked path ran, false when a PK-less table fell back to the single
         * streamed read (task book A1: the UI must say such tables cannot resume), null when
         * the question was not decided this cycle (incremental cycle, chunking switched off).
         */
        @lombok.Setter
        private Boolean resumableLoad;

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
            // A stored cursor the current strategy cannot parse — e.g. a full-compare "fc:"
            // fingerprint left behind by a switch to identity — must not be fed to the window
            // query as null: "> NULL" matches no rows, yet the cycle would still advance the
            // cursor to the watermark and permanently skip the undelivered range. Treat it as
            // no cursor at all; the full load re-delivers everything and stores a value the
            // strategy can read.
            boolean cursorUnusable = progress.getLastSyncValue() != null
                    && strategy.isIncremental()
                    && parseCursor(progress.getLastSyncValue(), strategy) == null;
            if (cursorUnusable) {
                log.warn("Stored cursor '{}' for {} is unreadable under the {} strategy;"
                                + " falling back to a full reload",
                        progress.getLastSyncValue(), table.getName(), strategy.getKind());
            }

            boolean isInitialLoad = progress.getInitialLoadDone() == null
                    || !progress.getInitialLoadDone()
                    || progress.getLastSyncValue() == null
                    || cursorUnusable;

            if (isInitialLoad) {
                result.initialLoad = true;
                return fullLoad(sourceConn, targetConn, table, targetTable, strategy, ctx, result);
            }
            if (strategy.getKind() == CursorStrategy.Kind.FULL_COMPARE) {
                if (!table.hasPrimaryKey()) {
                    // The one-time seed above already copied current contents. Re-running the
                    // full load on every later cycle would plain-INSERT every row (no key means
                    // no idempotent upsert), so the target grows without bound — and without a
                    // key inserts cannot be told from updates anyway. Skip until a key exists.
                    result.skipped = true;
                    result.skipReason = "Recurring full load skipped for keyless table "
                            + table.getName() + ": add a primary key to keep it in sync";
                    log.warn("Table {} has no primary key under FULL_COMPARE: only the one-time"
                            + " seed is applied; later cycles skip it to avoid duplicating rows."
                            + " Add a primary key to keep the table in sync.", table.getName());
                    return result;
                }
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

        Object upperBound = watermark == null ? null : parseCursor(watermark, strategy);
        if (watermark != null && upperBound == null) {
            // The source reported a high-watermark we cannot turn back into a bind value
            // (an exotic temporal type, a corrupted cursor, ...). Loading unbounded would
            // desync the stored cursor from the copied rows, and List.of(null) would NPE;
            // fail this cycle instead so the next one retries cleanly.
            log.warn("Cannot parse high-watermark '{}' for {} (column {}); skipping this cycle",
                    watermark, table.getName(), strategy.getColumn());
            result.error = "Unparseable high-watermark: " + watermark;
            return result;
        }

        // Initial loads of keyed tables run as a chain of short keyset chunks, each committed
        // and checkpointed on its own, so an interrupted load resumes mid-table instead of
        // re-reading everything. Chunk-size 0, a keyless table, or a missing checkpoint
        // repository keeps the classic single streamed read below.
        if (result.initialLoad
                && chunkedInitialLoad(sourceConn, targetConn, table, targetTable, strategy,
                        watermark, upperBound, ctx, result)) {
            return result;
        }

        boolean insertFirst = useInsertFirst(targetConn, ctx, targetTable,
                table.getPrimaryKeys());

        SqlDialect sourceDialect = ctx.getSourceDialect();
        String selectSql = "SELECT * FROM "
                + sourceDialect.qualify(ctx.getSourceSchema(), table.getName());
        // Bound the read by the same watermark, so the load and the cursor agree exactly.
        if (watermark != null) {
            String quotedCursor = KeysetChunker.sqlRef(sourceDialect, strategy);
            if (strategy.isPseudoColumn()) {
                // ORA_ROWSCN is never NULL; quoting the pseudo-column would be a syntax error.
                selectSql += " WHERE " + quotedCursor + " <= ?";
            } else {
                // NULL cursor rows fall outside every window comparison. Pull them in with the
                // bounded rows so they are not silently lost: on a keyed table the incremental
                // path keeps re-delivering them (the upsert absorbs the replay), while here in
                // the seed they are delivered exactly once even for a keyless table.
                selectSql += " WHERE " + quotedCursor + " <= ? OR " + quotedCursor + " IS NULL";
            }
        }

        // full-compare 表没有游标，每一轮都会走这条全量路径，逐轮 info 是纯粹的日志噪音；
        // 增量表只在首次加载走一次，那条 info 值得留下。
        if (strategy.getKind() == CursorStrategy.Kind.FULL_COMPARE) {
            log.debug("Full load of {} -> {}", table.getName(), targetTable);
        } else {
            log.info("Full load of {} -> {}", table.getName(), targetTable);
        }
        WriteStats stats = copyRows(sourceConn, targetConn, selectSql,
                watermark != null ? List.of(upperBound) : List.of(),
                table, targetTable, ctx, null, null,
                fullLoadBatchSize(ctx), fullLoadFetchSize(ctx), insertFirst);

        result.rowsWritten = stats.read;
        applyStats(result, stats);
        // Same rewind as the incremental path — but only on keyed tables: without a
        // primary key there is no upsert to absorb the replayed margin rows, so the lag
        // would duplicate them every cycle.
        result.newCursorValue = watermark == null ? null
                : applySafetyLagIfKeyed(table, watermark);

        // 非增量策略（full-compare）没有真正的游标，我们存一个"行数指纹"到 lastSyncValue，
        // 让下一轮知道初始加载已经做过了，否则 lastSyncValue == null 会让每一轮都当成首次加载。
        // 这一轮到底算不算"有变化"，由 rowsChanged 说话（驱动报回来的真实影响行数），
        // 而不是靠比对行数——行数不变但某个字段改了的情况，指纹是察觉不到的。
        if (strategy.getKind() == CursorStrategy.Kind.FULL_COMPARE) {
            result.newCursorValue = CursorStrategy.FULL_COMPARE_ROWCOUNT_PREFIX + stats.read;
        }
        return result;
    }

    /**
     * Whether this initial load may write plain {@code INSERT}s: an Oracle target whose table
     * is still empty.
     *
     * <p>On an empty table the upsert's match test can never fire, and the benchmark measured
     * the INSERT that remains at roughly 2.5x the MERGE throughput. Everything that is not a
     * <em>confirmed</em>-empty Oracle table keeps the idempotent upsert: an INSERT against an
     * existing key raises a unique violation, so "could not tell" must never mean "empty".
     */
    boolean useInsertFirst(Connection targetConn, SyncContext ctx, String targetTable,
                           List<String> pkColumns) {
        if (!properties.isOracleInitialLoadInsertFirst() || pkColumns.isEmpty()
                || ctx.getTargetType() != DatabaseType.ORACLE) {
            return false;
        }
        String probe = "SELECT 1 FROM "
                + ctx.getTargetDialect().qualify(ctx.getTargetSchema(), targetTable)
                + " WHERE ROWNUM = 1";
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(probe)) {
            boolean empty = !rs.next();
            if (empty) {
                log.info("Initial load of {} writes INSERT: the target table is empty",
                        targetTable);
            } else {
                log.debug("Initial load of {} keeps MERGE: the target table already has rows",
                        targetTable);
            }
            return empty;
        } catch (SQLException e) {
            log.debug("Could not probe {} for the initial-load INSERT path ({}); keeping MERGE",
                    targetTable, e.getMessage());
            return false;
        }
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
        if (upperBound == null) {
            // Same reasoning as fullLoad: a watermark we cannot re-parse must not be
            // bound as a window edge (null would silently match no rows and still
            // advance the cursor past them). Fail the cycle and retry next round.
            log.warn("Cannot parse high-watermark '{}' for {} (column {}); skipping this cycle",
                    watermark, table.getName(), strategy.getColumn());
            result.error = "Unparseable high-watermark: " + watermark;
            return result;
        }
        if (lastCursor != null && !isGreaterThan(upperBound, lastCursor)) {
            // The watermark has not moved past what we already synced.
            return result;
        }

        SqlDialect sourceDialect = ctx.getSourceDialect();
        String cursorCol = KeysetChunker.sqlRef(sourceDialect, strategy);
        // Half-open lower bound, closed upper bound: each row is delivered exactly once per
        // window, and the closed upper bound is what makes the window immune to concurrent
        // writes arriving mid-read.
        String selectSql = "SELECT * FROM "
                + sourceDialect.qualify(ctx.getSourceSchema(), table.getName())
                + " WHERE (" + cursorCol + " > ? AND " + cursorCol + " <= ?)";
        if (strategy.isPseudoColumn()) {
            // ORA_ROWSCN is never NULL: no margin rows to re-deliver and no keyless-table
            // warning to raise.
        } else if (table.hasPrimaryKey()) {
            // NULL cursor rows cannot belong to any window. Re-deliver them every cycle;
            // with a primary key the upsert makes the replay a no-op. A NOT NULL cursor
            // column (or a generated timestamp) removes this cost entirely.
            selectSql += " OR " + cursorCol + " IS NULL";
        } else {
            // A keyless replay would duplicate those rows, so they are left to the
            // one-time seed; surface the limitation instead of dropping it silently.
            warnNullCursorRows(sourceConn, table, strategy, ctx);
        }
        selectSql += " ORDER BY " + cursorCol;

        List<Object> params = new ArrayList<>();
        params.add(lastCursor);
        params.add(upperBound);

        WriteStats stats = copyRows(sourceConn, targetConn, selectSql, params, table, targetTable, ctx);
        result.rowsWritten = stats.read;
        applyStats(result, stats);

        if (stats.read > 0) {
            // 只有目标真的动了才值得在 INFO 上说话。安全余量每轮都会把边界那一秒的行重发
            // 一遍（见上面 applySafetyLagIfKeyed 的说明），空闲项目上那是"每表每秒一行
            // Synced N row(s) (0 changed)"——用户看到只会以为数据一直在变。SyncTaskRunner
            // 那句 "Sync of 'X' finished" 用的就是同一条约定：没有改动就留在 DEBUG。
            if (result.getRowsChanged() > 0) {
                log.info("Synced {} row(s) ({} changed) for {} in window ({} .. {}]",
                        stats.read, result.getRowsChanged(), table.getName(),
                        progress.getLastSyncValue(), watermark);
            } else {
                log.debug("Re-read {} row(s) and wrote nothing for {} in window ({} .. {}]"
                        + " (safety-margin replay)",
                        stats.read, table.getName(), progress.getLastSyncValue(), watermark);
            }
        }

        // Rewind the persisted watermark slightly, so a source transaction that committed
        // out of clock order is re-read next cycle instead of being skipped forever.
        // Decided by the watermark's VALUE, not the strategy kind: a temporal column
        // resolved as IDENTITY (e.g. a create-time column) still parses as an instant and
        // gets the lag; a numeric identity watermark does not parse and passes through.
        // The lag itself is keyed-table only: replaying margin rows into a keyless target
        // duplicates them, which is the bug this used to cause every cycle.
        result.newCursorValue = applySafetyLagIfKeyed(table, watermark);
        return result;
    }

    /**
     * Rewinds a timestamp watermark for a <em>keyed</em> table only.
     *
     * <p>On a keyless table the safety lag is deliberately skipped: its margin rows would
     * be plain INSERTs (the emulated upsert cannot match them), so every cycle would add
     * duplicates. Keeping the exact watermark loses the late-commit protection instead —
     * the lesser evil, since duplicates are silently wrong while the uncovered window is
     * bounded by the lag and rows only miss when they commit after the read snapshot.
     */
    private String applySafetyLagIfKeyed(TableMeta table, String watermark) {
        if (!table.hasPrimaryKey()) {
            return watermark;
        }
        return applySafetyLag(watermark);
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
        String sql = "SELECT MAX(" + KeysetChunker.sqlRef(dialect, strategy) + ") FROM "
                + dialect.qualify(ctx.getSourceSchema(), table.getName());
        try (Statement st = sourceConn.createStatement()) {
            JdbcUtil.applyQueryTimeout(st, properties.getQueryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    Object value = readWatermarkValue(rs, strategy);
                    if (!rs.wasNull() && value != null) {
                        return JdbcRowMapper.cursorToString(value);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Reads the aggregate value using the accessor matching the cursor column's type.
     *
     * <p>The strategy {@link CursorStrategy.Kind Kind} says how window semantics work; it
     * must not decide the JDBC read. In particular a TIME (or DATE) column the user picked
     * explicitly is still Kind TIMESTAMP, and reading it with {@code getTimestamp} produced
     * an instant the TIME parser could not round-trip ("Unparseable high-watermark" every
     * cycle), while a temporal creation column with Kind IDENTITY went through
     * {@code getObject} and came back as a driver-specific {@code LocalDateTime} whose
     * UTC-instant encoding shifted the window by the server timezone.
     */
    private Object readWatermarkValue(ResultSet rs, CursorStrategy strategy) throws SQLException {
        switch (strategy.getJdbcType()) {
            case java.sql.Types.TIME:
            case java.sql.Types.TIME_WITH_TIMEZONE:
                // Ruling R5: the literal, not rs.getTime — TIME watermarks legally exceed
                // 24h or go negative, which getTime rejects or wraps; cursorToString stores
                // the literal text and cursorFromString binds it back exactly.
                return rs.getString(1);
            case java.sql.Types.DATE:
                return rs.getDate(1);
            case java.sql.Types.TIMESTAMP:
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
                return rs.getTimestamp(1);
            default:
                // Kind IDENTITY with a numeric column.
                return rs.getObject(1);
        }
    }

    /**
     * Counts rows whose cursor column is NULL, so a keyless incremental table's uncovered
     * rows produce a visible warning instead of vanishing silently. Cheap (one indexed
     * NULL-range lookup) and only run on the keyless path.
     */
    private void warnNullCursorRows(Connection sourceConn, TableMeta table,
                                    CursorStrategy strategy, SyncContext ctx) {
        if (strategy.isPseudoColumn()) {
            // ORA_ROWSCN is never NULL; counting NULLs would be a pointless full scan.
            return;
        }
        SqlDialect dialect = ctx.getSourceDialect();
        String sql = "SELECT COUNT(*) FROM "
                + dialect.qualify(ctx.getSourceSchema(), table.getName())
                + " WHERE " + dialect.quoteIdentifier(strategy.getColumn()) + " IS NULL";
        try (Statement st = sourceConn.createStatement()) {
            JdbcUtil.applyQueryTimeout(st, properties.getQueryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                if (rs.next() && rs.getLong(1) > 0) {
                    log.warn("Table {} has {} row(s) with a NULL cursor column {} and no primary"
                                    + " key: the seed delivers them once but later cycles cannot"
                                    + " re-deliver them without duplicating. Fill the column or add"
                                    + " a primary key.",
                            table.getName(), rs.getLong(1), strategy.getColumn());
                }
            }
        } catch (SQLException e) {
            log.debug("Could not count NULL-cursor rows for {}: {}",
                    table.getName(), e.getMessage());
        }
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
        // A pseudo-column cursor (ORA_ROWSCN) exists only on the source; the target has no
        // column to bound the comparison with, so the bounded audit cannot run for it.
        if (!strategy.isIncremental() || strategy.getColumn() == null
                || strategy.isPseudoColumn()) {
            return null;
        }
        Object cursor = parseCursor(progress.getLastSyncValue(), strategy);
        if (cursor == null) {
            // Nothing claimed as delivered yet; the next cycle's initial load covers it.
            return null;
        }

        SqlDialect targetDialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(table.getName());

        // The target cursor column can be coarser than the source's — a legacy table
        // created as DATETIME(0) before the MySQL dialect emitted DATETIME(6). Drivers
        // ROUND fractional seconds on insert, so a row exactly at the bound can be stored
        // up to half a unit ABOVE it; comparing the raw bound on both sides then reports
        // the target as permanently short and fires corrective reload after reload.
        // Truncate the bound to the target column's own scale and use it for both counts:
        // every row the truncated bound counts at the source was synced, and its rounded
        // stored value still satisfies the truncated bound — no false deficit. Rows whose
        // stored value rounds past it simply drop out of both counts, and an unsynced row
        // just above the bound can only inflate the TARGET side, which the one-sided test
        // ignores by design.
        Object bound = cursor;
        if (cursor instanceof Timestamp) {
            int scale = targetCursorScale(targetConn, ctx.getTargetSchema(), targetTable,
                    strategy.getColumn());
            bound = truncateToScale((Timestamp) cursor, scale);
        }

        SqlDialect sourceDialect = ctx.getSourceDialect();
        long sourceRows = countAtOrBelow(sourceConn,
                sourceDialect.qualify(ctx.getSourceSchema(), table.getName()),
                sourceDialect.quoteIdentifier(strategy.getColumn()), bound);

        long targetRows = countAtOrBelow(targetConn,
                targetDialect.qualify(ctx.getTargetSchema(), targetTable),
                // Column names are copied verbatim from the source, so the cursor column is
                // spelled the same on both sides; only the quoting differs.
                targetDialect.quoteIdentifier(strategy.getColumn()), bound);

        return new RowCountAudit(sourceRows, targetRows);
    }

    /** DECIMAL_DIGITS of the target cursor column, or -1 when the driver will not say. */
    private int targetCursorScale(Connection targetConn, String schema, String table,
                                  String column) {
        try {
            DatabaseMetaData md = targetConn.getMetaData();
            int scale = columnScale(md, null, schema, table, column);
            if (scale < 0) {
                // MySQL-family drivers carry the database in the CATALOG and report a
                // null schema; retry with the schema name in the catalog slot.
                scale = columnScale(md, schema, null, table, column);
            }
            return scale;
        } catch (SQLException e) {
            log.debug("Cannot read cursor column scale for {}.{}: {}", table, column, e.getMessage());
            return -1;
        }
    }

    private int columnScale(DatabaseMetaData md, String catalog, String schema,
                            String table, String column) throws SQLException {
        try (ResultSet rs = md.getColumns(catalog, schema, table, column)) {
            if (rs.next()) {
                return rs.getInt("DECIMAL_DIGITS");
            }
        }
        // Oracle and DB2 fold identifiers to upper case in their dictionaries.
        try (ResultSet rs = md.getColumns(
                catalog == null ? null : catalog.toUpperCase(),
                schema == null ? null : schema.toUpperCase(),
                table.toUpperCase(), column.toUpperCase())) {
            if (rs.next()) {
                return rs.getInt("DECIMAL_DIGITS");
            }
        }
        return -1;
    }

    /** Drops precision below the given scale, so the bound compares like a stored value. */
    private Timestamp truncateToScale(Timestamp ts, int scale) {
        if (scale < 0 || scale >= 9) {
            return ts;
        }
        long unit = 1L;
        for (int i = 0; i < 9 - scale; i++) {
            unit *= 10L;
        }
        long remainder = Math.floorMod(ts.toInstant().getNano(), unit);
        return remainder == 0 ? ts : Timestamp.from(ts.toInstant().minusNanos(remainder));
    }

    private long countAtOrBelow(Connection conn, String qualifiedTable, String quotedColumn,
                                Object cursor) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable + " WHERE " + quotedColumn + " <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            JdbcUtil.applyQueryTimeout(ps, properties.getQueryTimeoutSeconds());
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
        if (candidate instanceof java.sql.Date && reference instanceof java.sql.Date) {
            return ((java.sql.Date) candidate).toLocalDate()
                    .isAfter(((java.sql.Date) reference).toLocalDate());
        }
        if (candidate instanceof java.sql.Time && reference instanceof java.sql.Time) {
            return ((java.sql.Time) candidate).after((java.sql.Time) reference);
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
        return copyRows(sourceConn, targetConn, selectSql, params, table, targetTable, ctx,
                null, null);
    }

    /**
     * @param pkTrack primary-key column names to observe per row, or null to not track
     * @param sink    receives the key values of the last row read; the chunked full load
     *                persists them as its resume boundary
     */
    private WriteStats copyRows(Connection sourceConn, Connection targetConn, String selectSql,
                                List<Object> params, TableMeta table, String targetTable,
                                SyncContext ctx, List<String> pkTrack, PkCursor sink)
            throws SQLException {
        // Incremental windows and CDC replay keep the shared, conservative budget.
        return copyRows(sourceConn, targetConn, selectSql, params, table, targetTable, ctx,
                pkTrack, sink, ctx.getBatchSize(), properties.getFetchSize());
    }

    /** Rows per batch the full load should commit with; hand-built contexts fall back. */
    private int fullLoadBatchSize(SyncContext ctx) {
        return ctx.getFullLoadBatchSize() > 0 ? ctx.getFullLoadBatchSize() : ctx.getBatchSize();
    }

    /** Fetch size the full-load cursor should use; hand-built contexts fall back. */
    private int fullLoadFetchSize(SyncContext ctx) {
        return ctx.getFullLoadFetchSize() > 0
                ? ctx.getFullLoadFetchSize() : properties.getFetchSize();
    }

    /**
     * One copy pass with an explicit batch/fetch budget, so the full load can commit at a
     * cadence the incremental and CDC paths must not inherit.
     */
    private WriteStats copyRows(Connection sourceConn, Connection targetConn, String selectSql,
                                List<Object> params, TableMeta table, String targetTable,
                                SyncContext ctx, List<String> pkTrack, PkCursor sink,
                                int batchSize, int fetchSize)
            throws SQLException {
        // Incremental windows and CDC replay never take the INSERT-only initial-load shortcut:
        // their rows routinely hit keys that already exist, which is exactly what MERGE is for.
        return copyRows(sourceConn, targetConn, selectSql, params, table, targetTable, ctx,
                pkTrack, sink, batchSize, fetchSize, false);
    }

    /**
     * As above, with the writer's statement shape decided by the caller: an initial load into
     * a confirmed-empty Oracle table writes {@code INSERT}, everything else keeps the upsert.
     */
    private WriteStats copyRows(Connection sourceConn, Connection targetConn, String selectSql,
                                List<Object> params, TableMeta table, String targetTable,
                                SyncContext ctx, List<String> pkTrack, PkCursor sink,
                                int batchSize, int fetchSize, boolean insertFirst)
            throws SQLException {
        SqlDialect targetDialect = ctx.getTargetDialect();
        List<String> pkColumns = table.getPrimaryKeys();

        boolean originalAutoCommit = targetConn.getAutoCommit();
        targetConn.setAutoCommit(false);

        // SQL Server rejects explicit identity-column values with error 544 unless the
        // session has run SET IDENTITY_INSERT ON for the target table. Only one table per
        // session can be armed, so it is switched off again in the finally below.
        boolean hasIdentityColumn = table.getColumns().stream().anyMatch(c -> c.isAutoIncrement());
        String identityOn = hasIdentityColumn
                ? targetDialect.getSetIdentityInsertSql(ctx.getTargetSchema(), targetTable, true)
                : null;

        WriteStats total = new WriteStats();
        boolean identityArmed = false;
        try (PreparedStatement select = sourceConn.prepareStatement(selectSql)) {
            JdbcUtil.applyQueryTimeout(select, properties.getQueryTimeoutSeconds());
            if (identityOn != null) {
                try (Statement idStmt = targetConn.createStatement()) {
                    idStmt.execute(identityOn);
                }
                identityArmed = true;
            }
            // The dialect decides how to ask its driver for a streamed read: MySQL-protocol
            // sources need the Integer.MIN_VALUE sentinel, everyone else honors the
            // configured fetch size.
            select.setFetchSize(ctx.getSourceDialect().streamingFetchSize(fetchSize));
            for (int i = 0; i < params.size(); i++) {
                select.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = select.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = JdbcRowMapper.columnNames(md);
                List<Integer> jdbcTypes = JdbcRowMapper.columnTypes(md);
                List<String> typeNames = JdbcRowMapper.columnTypeNames(md);
                // Target metadata by name, so a column's type can be refined by the dialect:
                // a text LOB lands in a CLOB and must be bound (and null-typed) as one.
                Map<String, ColumnMeta> metaByColumn = new HashMap<>();
                for (ColumnMeta column : table.getColumns()) {
                    metaByColumn.put(column.getName().toUpperCase(java.util.Locale.ROOT), column);
                }
                Map<String, Integer> typeByColumn = new HashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    int effectiveType = effectiveTargetType(ctx.getSourceType(), jdbcTypes.get(i),
                            typeNames.get(i), md.getPrecision(i + 1));
                    ColumnMeta columnMeta = metaByColumn.get(
                            columns.get(i).toUpperCase(java.util.Locale.ROOT));
                    typeByColumn.put(columns.get(i), targetDialect.bindJdbcType(
                            columnMeta, ctx.getSourceType(), effectiveType));
                }

                // An INSERT has no match clause, so the key columns only decide that shape —
                // the cursor and replay logic above keeps using the real key list.
                List<String> statementKeys = insertFirst ? List.of() : pkColumns;
                String upsertSql = targetDialect.getUpsertSql(ctx.getTargetSchema(), targetTable,
                        columns, statementKeys, typeByColumn,
                        canGuardUnchangedRows(targetConn, ctx, targetTable, columns, pkColumns));
                List<String> bindOrder = targetDialect.upsertBindOrder(columns, statementKeys);

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

                ColumnCleanser cleanser = ColumnCleanser.forTable(
                        ctx.getConfig(), table.getName());
                boolean emulated = emulateUpsert && !pkColumns.isEmpty();
                LobReadPolicy lobPolicy = buildLobPolicy(table, columns, md,
                        pkColumns, pkTrack, cleanser, emulated);
                // An emulated upsert binds the same row up to three times, so its large values
                // must be replayable and therefore live in a temp file.
                LobSpillSession spillSession = lobPolicy.largeMode() == LobReadPolicy.Mode.SPILL
                        ? spillStore().open() : null;
                ThroughputMeter throughput = new ThroughputMeter(table.getName());
                try {
                    total = emulated
                            ? writeWithEmulatedUpsert(targetConn, targetTable, columns, pkColumns,
                                    typeByColumn, rs, jdbcTypes, typeNames, ctx, cleanser, pkTrack,
                                    sink, throughput, batchSize, lobPolicy, spillSession)
                            : writeWithNativeUpsert(targetConn, upsertSql, bindOrder, typeByColumn,
                                    rs, columns, jdbcTypes, typeNames, ctx, cleanser, pkTrack,
                                    sink, throughput, batchSize, lobPolicy, spillSession);
                } finally {
                    if (spillSession != null) {
                        spillSession.close();
                    }
                }
                throughput.logSummary();
            }
            targetConn.commit();
        } catch (LockLostException e) {
            // Discard the uncommitted tail before autocommit is restored (which would otherwise
            // commit it). The committed batches are idempotent and replay to a no-op.
            try {
                targetConn.rollback();
            } catch (SQLException rollbackError) {
                log.error("Rollback after lost lease failed for {}: {}",
                        targetTable, rollbackError.getMessage());
            }
            throw e;
        } catch (SQLException e) {
            try {
                targetConn.rollback();
            } catch (SQLException rollbackError) {
                log.error("Rollback failed for {}: {}", targetTable, rollbackError.getMessage());
            }
            throw e;
        } catch (RuntimeException e) {
            // Covers a stop request and any unchecked defect in the row mapping or in a driver
            // that throws past its declared contract. Without this, the finally block's
            // setAutoCommit(true) makes the driver commit whatever the failing batch applied.
            try {
                targetConn.rollback();
            } catch (SQLException rollbackError) {
                log.error("Rollback after an unchecked failure failed for {}: {}",
                        targetTable, rollbackError.getMessage());
            }
            throw e;
        } finally {
            if (identityArmed) {
                String identityOff = targetDialect.getSetIdentityInsertSql(
                        ctx.getTargetSchema(), targetTable, false);
                if (identityOff != null) {
                    try (Statement idStmt = targetConn.createStatement()) {
                        idStmt.execute(identityOff);
                    } catch (SQLException e) {
                        // Session-scoped: a connection returned to the pool could otherwise
                        // stay armed, but the pool hands this connection out per table; log.
                        log.debug("Could not switch IDENTITY_INSERT off for {}: {}",
                                targetTable, e.getMessage());
                    }
                }
            }
            try {
                targetConn.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                log.debug("Could not restore autocommit: {}", e.getMessage());
            }
        }
        return total;
    }

    /**
     * Whether the target's own column types allow the upsert to carry a "something really
     * differs" condition, i.e. every non-key column's values can be compared with {@code =}.
     *
     * <p>This is what stops an idle polling project from logging a fake update every cycle:
     * the cursor's safety margin replays the same second, the target rewrites those rows, and
     * a target that cannot say "the values were already there" (Oracle, SQL Server, DB2,
     * PostgreSQL — everyone but MySQL) books each replay as a change. Deciding it here, from
     * live target metadata, is deliberate: the bind-type map beside it carries the source's
     * types, and a VARCHAR source landing in a CLOB target is a shape this sync supports.
     *
     * <p>A target the dictionary cannot be read from is treated as unguardable — an extra log
     * line beats a statement that fails on every row.
     */
    private boolean canGuardUnchangedRows(Connection targetConn, SyncContext ctx,
                                          String targetTable, List<String> columns,
                                          List<String> pkColumns) {
        List<String> nonKey = new ArrayList<>();
        for (String column : columns) {
            if (!CollectionUtil.containsIgnoreCase(pkColumns, column)) {
                nonKey.add(column);
            }
        }
        if (nonKey.isEmpty()) {
            return false;
        }
        try {
            List<String> uncomparable = TargetTableVerifier.uncomparableColumns(targetConn,
                    ctx.getTargetSchema(), targetTable, ctx.getTargetType(), nonKey);
            if (!uncomparable.isEmpty()) {
                log.debug("Target table {} column(s) {} cannot be compared, so Jync rewrites a"
                        + " replayed row even when nothing changed", targetTable, uncomparable);
                return false;
            }
            return true;
        } catch (SQLException e) {
            log.debug("Could not read {}.{} column types ({}), leaving the upsert unguarded",
                    ctx.getTargetSchema(), targetTable, e.getMessage());
            return false;
        }
    }

    /**
     * JDBC type of the TARGET column for a source result column. The source metadata types
     * usually coincide with the built target types, except the columns the converter remaps
     * by database-specific name/width: MySQL YEAR (reported DATE) lands on SMALLINT,
     * MySQL BIT(n>1) lands on a width-band integer, and MySQL BIGINT UNSIGNED lands on
     * DECIMAL (its 2^64-1 top value overflows signed BIGINT). Binds and null-typing must
     * use the target type.
     */
    private static int effectiveTargetType(DatabaseType sourceType, int sourceJdbcType,
                                           String sourceTypeName, int precision) {
        if (sourceJdbcType == java.sql.Types.DATE
                && sourceTypeName != null
                && "YEAR".equalsIgnoreCase(sourceTypeName.trim())) {
            return java.sql.Types.SMALLINT;
        }
        if (sourceType != null
                && sourceType.getFamily() == DatabaseType.DialectFamily.MYSQL
                && sourceJdbcType == java.sql.Types.BIGINT
                && sourceTypeName != null) {
            String name = sourceTypeName.trim().toUpperCase();
            if (name.startsWith("BIGINT") && name.contains("UNSIGNED")) {
                return java.sql.Types.DECIMAL;
            }
        }
        if (sourceJdbcType == java.sql.Types.BIT && precision > 1) {
            if (precision <= 8) {
                return java.sql.Types.SMALLINT;
            }
            if (precision <= 16) {
                return java.sql.Types.INTEGER;
            }
            if (precision <= 63) {
                return java.sql.Types.BIGINT;
            }
            return java.sql.Types.DECIMAL;
        }
        return sourceJdbcType;
    }

    /**
     * Builds the per-table read policy: every PK/cursor/cleansed column stays inline, and
     * declared sizes come from the result-set metadata. An emulated-upsert target spills
     * large values so the row can be bound repeatedly; a native target keeps them one-shot
     * and the writer executes those rows immediately.
     */
    private LobReadPolicy buildLobPolicy(TableMeta table, List<String> columns,
                                         ResultSetMetaData md, List<String> pkColumns,
                                         List<String> pkTrack, ColumnCleanser cleanser,
                                         boolean emulatedUpsert) throws SQLException {
        SyncProperties.Lob config = properties.getLob();
        if (config == null || !config.isStreamingEnabled()) {
            return LobReadPolicy.disabled();
        }
        List<String> forceInline = new ArrayList<>();
        forceInline.addAll(pkColumns);
        if (pkTrack != null) {
            forceInline.addAll(pkTrack);
        }
        forceInline.addAll(cleanser.columns());

        Map<String, Long> declaredSizes = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            long precision = md.getPrecision(i + 1);
            if (precision > 0) {
                declaredSizes.put(columns.get(i), precision);
            }
        }
        return LobReadPolicy.forTable(table.getName(),
                        emulatedUpsert ? LobReadPolicy.Mode.SPILL
                                : LobReadPolicy.Mode.ONE_SHOT,
                        config.getInlineMaxBytes(), config.getForceInlineMaxBytes(), forceInline)
                .withDeclaredSizes(declaredSizes);
    }

    private LobSpillStore spillStore() {
        LobSpillStore store = lobSpillStore;
        if (store == null) {
            synchronized (this) {
                store = lobSpillStore;
                if (store == null) {
                    SyncProperties.Lob config = properties.getLob();
                    store = LobSpillStore.of(java.nio.file.Paths.get(config.getSpillDir()),
                            config.getSpillMaxBytes());
                    lobSpillStore = store;
                }
            }
        }
        return store;
    }

    /** Releases every {@code LobValue} in a row after it has been executed. */
    private static void releaseRowLobs(Map<String, Object> row) {
        for (Object value : row.values()) {
            LobValues.release(value);
        }
    }

    private static void releaseAll(List<Object> lobs) {
        for (Object lob : lobs) {
            LobValues.release(lob);
        }
        lobs.clear();
    }

    /** Adds the row's LOB values to the batch-owned list so a failure can free them. */
    private static void trackRowLobs(Map<String, Object> row, List<Object> pending) {
        for (Object value : row.values()) {
            if (LobValues.isLob(value)) {
                pending.add(value);
            }
        }
    }

    /** The normal path: one idempotent statement per row, executed in JDBC batches. */
    private WriteStats writeWithNativeUpsert(Connection targetConn, String upsertSql,
                                             List<String> bindOrder,
                                             Map<String, Integer> typeByColumn,
                                             ResultSet rs, List<String> columns,
                                             List<Integer> jdbcTypes, List<String> typeNames,
                                             SyncContext ctx, ColumnCleanser cleanser,
                                             List<String> pkTrack, PkCursor sink,
                                             ThroughputMeter throughput, int batchSize,
                                             LobReadPolicy lobPolicy,
                                             LobSpillSession spillSession)
            throws SQLException {
        WriteStats stats = new WriteStats();
        int inBatch = 0;
        // Row count alone cannot bound a batch of LOBs; the budget flushes by size too.
        com.qqmu.jync.service.lob.BatchByteBudget byteBudget =
                new com.qqmu.jync.service.lob.BatchByteBudget(
                        properties.getLob() == null ? 0L
                                : properties.getLob().getBatchMaxBytes());
        // Temporary LOBs bound into the batch stay alive until it executes, then are freed.
        com.qqmu.jync.service.lob.LobBinder.Tracker lobTracker =
                com.qqmu.jync.service.lob.LobBinder.newTracker();
        // Spilled values must survive until their batch executes; a failure frees them here.
        List<Object> pendingLobs = new ArrayList<>();
        try (PreparedStatement upsert = targetConn.prepareStatement(upsertSql)) {
            JdbcUtil.applyQueryTimeout(upsert, properties.getQueryTimeoutSeconds());
            // `mark` sits at the end of the previous row's write, so the gap it opens with is
            // the source's blocking rs.next() — the read the meter is meant to expose.
            long mark = System.nanoTime();
            // One map for every row: a million-row load would otherwise allocate a million.
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                JdbcRowMapper.readRowInto(rs, columns, jdbcTypes, typeNames,
                        ctx.getSourceType(), row, lobPolicy, spillSession);
                if (sink != null) {
                    // The keyset boundary is bound against the RAW source keys on the next
                    // page, so it must be captured before the cleanser rewrites them.
                    sink.values = extractPk(row, pkTrack);
                }
                long read = System.nanoTime();
                throughput.recordRead(read - mark);

                cleanser.apply(row);
                boolean immediate = false;
                long rowBytes = 0L;
                for (Object value : row.values()) {
                    if (LobValues.requiresImmediateExecution(value)) {
                        immediate = true;
                    }
                    rowBytes += com.qqmu.jync.service.lob.BatchByteBudget.sizeOf(value);
                }
                boolean budgetFlush = !immediate && byteBudget.wouldExceed(rowBytes);
                if ((immediate || budgetFlush) && inBatch > 0) {
                    // The stream is valid only until the cursor moves, and an oversized row
                    // must not be added to a batch that is already over budget.
                    executeBatch(upsert, targetConn, stats, ctx, throughput);
                    releaseAll(pendingLobs);
                    lobTracker.releaseAll();
                    inBatch = 0;
                    byteBudget.reset();
                }
                JdbcRowMapper.bind(upsert, row, bindOrder, typeByColumn,
                        ctx.getTargetDialect(), lobTracker);
                if (immediate) {
                    executeSingle(upsert, targetConn, stats, ctx, throughput);
                    lobTracker.releaseAll();
                    releaseRowLobs(row);
                } else {
                    upsert.addBatch();
                    inBatch++;
                    byteBudget.add(rowBytes);
                    trackRowLobs(row, pendingLobs);
                    if (inBatch >= batchSize) {
                        executeBatch(upsert, targetConn, stats, ctx, throughput);
                        releaseAll(pendingLobs);
                        lobTracker.releaseAll();
                        inBatch = 0;
                        byteBudget.reset();
                    }
                }
                stats.read++;

                throughput.recordWrite(System.nanoTime() - read);
                mark = System.nanoTime();
            }
            if (inBatch > 0) {
                long tailStart = System.nanoTime();
                executeBatch(upsert, targetConn, stats, ctx, throughput);
                releaseAll(pendingLobs);
                throughput.recordWrite(System.nanoTime() - tailStart);
            }
        } finally {
            releaseAll(pendingLobs);
            lobTracker.releaseAll();
        }
        return stats;
    }

    /** Executes one row outside the batch, for values whose stream ends when the cursor moves. */
    private void executeSingle(PreparedStatement ps, Connection conn, WriteStats stats,
                               SyncContext ctx, ThroughputMeter throughput) throws SQLException {
        int count;
        try {
            count = ps.executeUpdate();
        } catch (SQLException e) {
            throw new SQLException("Row write failed: " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        }
        throughput.recordBatch();
        commitAndRecord(conn, throughput);
        ctx.requireLease();
        if (count == 0) {
            return;
        }
        boolean classify = ctx.getTargetType() != null
                && ctx.getTargetType().upsertCountsDistinguishInsertFromUpdate();
        if (!classify) {
            stats.unclassified++;
        } else if (count == 1) {
            stats.inserted++;
        } else {
            stats.updated++;
        }
    }

    /**
     * Fallback for targets without a native upsert: try UPDATE by key, and INSERT only when
     * the update matched nothing. Two statements per row, but still idempotent.
     */
    private WriteStats writeWithEmulatedUpsert(Connection targetConn, String targetTable,
                                               List<String> columns, List<String> pkColumns,
                                               Map<String, Integer> typeByColumn, ResultSet rs,
                                               List<Integer> jdbcTypes, List<String> typeNames,
                                               SyncContext ctx, ColumnCleanser cleanser,
                                               List<String> pkTrack, PkCursor sink,
                                               ThroughputMeter throughput, int batchSize,
                                               LobReadPolicy lobPolicy,
                                               LobSpillSession spillSession)
            throws SQLException {
        SqlDialect dialect = ctx.getTargetDialect();
        GenericSqlDialect generic = dialect instanceof GenericSqlDialect
                ? (GenericSqlDialect) dialect : null;

        String updateSql = generic != null
                ? generic.getUpdateByPkSql(ctx.getTargetSchema(), targetTable, columns, pkColumns)
                : null;
        String insertSql = dialect.getUpsertSql(ctx.getTargetSchema(), targetTable, columns,
                List.of(), typeByColumn);

        List<String> updateBindOrder = new ArrayList<>();
        if (updateSql != null) {
            // SET clause values first, then the WHERE key values.
            for (String c : columns) {
                if (!CollectionUtil.containsIgnoreCase(pkColumns, c)) {
                    updateBindOrder.add(c);
                }
            }
            updateBindOrder.addAll(pkColumns);
        }

        WriteStats stats = new WriteStats();
        try (PreparedStatement insert = targetConn.prepareStatement(insertSql);
             PreparedStatement update = updateSql == null ? null
                     : targetConn.prepareStatement(updateSql)) {
            JdbcUtil.applyQueryTimeout(insert, properties.getQueryTimeoutSeconds());
            JdbcUtil.applyQueryTimeout(update, properties.getQueryTimeoutSeconds());
            int sinceCommit = 0;
            long mark = System.nanoTime();
            // Same reuse as the native writer: one row map for the whole pass.
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                JdbcRowMapper.readRowInto(rs, columns, jdbcTypes, typeNames,
                        ctx.getSourceType(), row, lobPolicy, spillSession);
                if (sink != null) {
                    // Same rule as the native writer: the boundary must stay a raw source key.
                    sink.values = extractPk(row, pkTrack);
                }
                long read = System.nanoTime();
                throughput.recordRead(read - mark);
                cleanser.apply(row);
                // A row may bind twice (update then insert, or update again after a unique
                // violation), so a streamed value must be replayable. The read policy spills
                // for this path; this is a guard against a future policy regression, and it
                // fails before the first bind rather than midway through the row.
                for (Object value : row.values()) {
                    if (!LobValues.isReplayable(value)) {
                        throw new SQLException("Table " + targetTable + " is written through"
                                + " an emulated upsert, which binds each row more than once;"
                                + " its large values must be replayable. Enable"
                                + " sync.lob.streaming-enabled (spill mode) or exclude the"
                                + " column.");
                    }
                }
                // A row may bind twice (update then insert, or update again after a unique
                // violation); each bind gets its own temporary LOBs, freed at row end.
                com.qqmu.jync.service.lob.LobBinder.Tracker lobTracker =
                        com.qqmu.jync.service.lob.LobBinder.newTracker();
                try {
                    int updated = 0;
                    if (update != null) {
                        JdbcRowMapper.bind(update, row, updateBindOrder, typeByColumn,
                                ctx.getTargetDialect(), lobTracker);
                        updated = update.executeUpdate();
                    }
                    if (updated == 0) {
                        JdbcRowMapper.bind(insert, row, columns, typeByColumn,
                                ctx.getTargetDialect(), lobTracker);
                        try {
                            // This path issues the INSERT and the UPDATE as separate
                            // statements, so it knows which one happened without the target
                            // having to say.
                            if (insert.executeUpdate() != 0) {
                                stats.inserted++;
                            }
                        } catch (SQLException e) {
                            if (isUniqueViolation(e) && update != null) {
                                // A concurrent writer may have inserted the same key between
                                // our UPDATE and INSERT. Retrying the update converges without
                                // failing.
                                JdbcRowMapper.bind(update, row, updateBindOrder, typeByColumn,
                                        ctx.getTargetDialect(), lobTracker);
                                if (update.executeUpdate() != 0) {
                                    stats.updated++;
                                }
                            } else if (isUniqueViolation(e)) {
                                // Key-only table — update is null exactly when every column
                                // is part of the key, so there is no UPDATE to retry. None is
                                // needed: a row already holding this key is identical to the
                                // one rejected, making re-delivery a no-op. Rethrowing here
                                // would wedge the table permanently, since every replay hits
                                // the same conflict.
                                log.debug("Row already present in key-only table {};"
                                        + " re-delivery is a no-op", targetTable);
                            } else {
                                throw e;
                            }
                        }
                    } else {
                        stats.updated++;
                    }
                } finally {
                    lobTracker.releaseAll();
                    releaseRowLobs(row);
                }
                stats.read++;
                if (++sinceCommit >= batchSize) {
                    commitAndRecord(targetConn, throughput);
                    throughput.recordBatch();
                    // Same renewal point as the native-batch path: abort before more rows once
                    // the lease is lost. The committed tail stays; the rest replays next time.
                    ctx.requireLease();
                    sinceCommit = 0;
                }
                throughput.recordWrite(System.nanoTime() - read);
                mark = System.nanoTime();
            }
        }
        return stats;
    }

    /** Mutable single-slot holder: primary-key values of the last row a copy pass read. */
    private static final class PkCursor {
        private List<Object> values;
    }

    /**
     * Pulls the tracked key columns out of one mapped row. Column names may differ in case
     * between the result set and the metadata-derived PK list (Oracle reports upper case),
     * so the lookup falls back to a case-insensitive scan.
     */
    private static List<Object> extractPk(Map<String, Object> row, List<String> pkColumns) {
        List<Object> values = new ArrayList<>(pkColumns.size());
        for (String pk : pkColumns) {
            Object value = row.get(pk);
            if (value == null && !row.containsKey(pk)) {
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(pk)) {
                        value = e.getValue();
                        break;
                    }
                }
            }
            values.add(value);
        }
        return values;
    }

    /**
     * Keyset-chunked initial full load with an intra-table resume point.
     *
     * <p>Instead of one table-long streamed read, the table is walked as a chain of short
     * bounded pages ({@code WHERE pk > last ORDER BY pk LIMIT chunkSize}). Each page is copied
     * through {@link #copyRows}, which commits it on the target, and only then is the page's
     * boundary persisted to {@code chunk_checkpoint}. A crash therefore costs at most one page
     * of re-read work: the restarted load resumes after the last checkpointed boundary, and
     * the replayed partial page is absorbed by the idempotent upserts.
     *
     * <p>Skips itself (returns false, caller keeps the classic path) when the table has no PK,
     * chunking is switched off, or no checkpoint repository is wired. On success it fills the
     * result exactly like the classic full load does — row counts, the safety-lagged cursor for
     * keyed incremental strategies, the {@code fc:} fingerprint for FULL_COMPARE — and clears
     * the table's checkpoints, which only ever describe an in-flight load. Note that after a
     * resumed load {@code rowsWritten} counts this pass's rows, not the whole table's.
     */
    private boolean chunkedInitialLoad(Connection sourceConn, Connection targetConn,
                                       TableMeta table, String targetTable,
                                       CursorStrategy strategy, String watermark,
                                       Object upperBound, SyncContext ctx,
                                       TableSyncResult result) throws SQLException {
        FullLoadRoute route = FullLoadRoute.route(table);
        if (checkpoints != null && properties.getChunkSize() > 0
                && route == FullLoadRoute.STREAMING_FALLBACK) {
            // No key to chunk on: the classic streamed read runs instead and cannot resume.
            // Recorded for the UI rather than left implicit (task book A1).
            result.resumableLoad = false;
        }
        if (checkpoints == null || properties.getChunkSize() <= 0
                || route == FullLoadRoute.STREAMING_FALLBACK) {
            return false;
        }
        List<ChunkCheckpoint> saved = checkpoints
                .findByProjectIdAndTableName(ctx.projectId(), table.getName());
        int parallelism = resolveParallelism(route, table, ctx);
        // Asked once per table load, never per chunk: from the second chunk on the table holds
        // the first chunk's rows and would answer "not empty", quietly dropping every later
        // chunk back to MERGE. The ranges of a parallel load are disjoint, so a table that was
        // empty at this point is a safe INSERT target for every worker as well.
        boolean insertFirst = useInsertFirst(targetConn, ctx, targetTable,
                table.getPrimaryKeys());
        boolean done = parallelism > 1
                ? parallelChunkedLoad(sourceConn, targetConn, table, targetTable, strategy,
                        watermark, upperBound, ctx, result, saved, parallelism, insertFirst)
                : sequentialChunkedLoad(sourceConn, targetConn, table, targetTable, strategy,
                        watermark, upperBound, ctx, result, saved, insertFirst);
        if (done) {
            result.resumableLoad = true;
        }
        return done;
    }

    /**
     * One worker walking the whole key span: each page starts where the previous page's
     * checkpoint ended, so a resume never re-reads more than the one interrupted page.
     */
    private boolean sequentialChunkedLoad(Connection sourceConn, Connection targetConn,
                                          TableMeta table, String targetTable,
                                          CursorStrategy strategy, String watermark,
                                          Object upperBound, SyncContext ctx,
                                          TableSyncResult result, List<ChunkCheckpoint> saved,
                                          boolean insertFirst)
            throws SQLException {
        // Checkpoints left by an earlier parallel run are organized into key ranges this path
        // does not walk; dropping them and reloading is safe (idempotent upserts) and keeps the
        // sequential resume model single-valued. Range 0 of a parallel split is a slice of the
        // span, not the whole table, so its non-null bounds also mark it foreign.
        boolean foreignModel = saved.stream().anyMatch(c ->
                (c.getRangeIndex() != null && c.getRangeIndex() != 0)
                        || ChunkCheckpoint.STATUS_RANGE_DONE.equals(c.getStatus())
                        || c.getRangeLower() != null || c.getRangeUpper() != null);
        if (foreignModel) {
            log.info("Discarding parallel-range checkpoints for {}: reloading sequentially",
                    table.getName());
            checkpoints.deleteByProjectIdAndTableName(ctx.projectId(), table.getName());
            saved = List.of();
        }
        List<String> pk = table.getPrimaryKeys();
        int chunkSize = properties.getChunkSize();

        RangeResume resume = resumePoint(saved, 0, table);
        List<Object> lastPk = resume.boundary;
        int chunkIndex = resume.nextChunkIndex;
        if (lastPk != null) {
            log.info("Resuming chunked full load of {} from chunk {} (keyset boundary kept)",
                    table.getName(), chunkIndex);
        }

        int totalRead = 0;
        while (true) {
            // Stop-requested abort (final review FIX 2): a CDC disable/rebaseline/shutdown
            // whose join budget expired INTERRUPTS this thread; without this check the load
            // kept writing to the target for hours after "stopped" (the per-batch
            // ctx.requireLease() never fires — the lease is the consumer's own and stays
            // fresh). A chunk boundary is a safe abort point: the previous chunk is
            // committed AND checkpointed, so the load resumes exactly here and a CDC
            // baseline stays not-done (baseline_done only flips after the last chunk). The
            // interrupt flag is deliberately left SET — CdcBaselineService and CdcConsumer
            // read it to tell this abort from a real failure (no ERROR hat on a legitimate
            // stop). Blast radius: this is the shared polling engine, but in production
            // only CdcConsumerManager ever interrupts, and only CDC consumer threads —
            // polling cycles (Quartz fires, sync-now) are never interrupted mid-run, so
            // polling behavior is unchanged; at shutdown an interrupt-abort is the desired
            // behavior for both modes.
            if (Thread.currentThread().isInterrupted()) {
                throw new StopRequestedException("Full load of " + table.getName()
                        + " aborted at chunk " + chunkIndex
                        + ": stop requested (thread interrupted)");
            }
            List<Object> params = new ArrayList<>();
            String pageSql = KeysetChunker.pageSql(ctx.getSourceDialect(), ctx.getSourceSchema(),
                    table.getName(), pk, lastPk, null, strategy, upperBound, chunkSize, params);
            PkCursor sink = new PkCursor();
            WriteStats stats = copyRows(sourceConn, targetConn, pageSql, params,
                    table, targetTable, ctx, pk, sink,
                    fullLoadBatchSize(ctx), fullLoadFetchSize(ctx), insertFirst);
            if (stats.read == 0) {
                break;
            }
            totalRead += stats.read;
            result.rowsInserted += stats.inserted;
            result.rowsUpdated += stats.updated;
            result.rowsChangedUnclassified += stats.unclassified;
            // Checkpoint only after the page's rows are committed on the target: a page that
            // died mid-write leaves no boundary behind and is simply re-read next time.
            checkpoints.save(new ChunkCheckpoint(ctx.projectId(), table.getName(), 0, chunkIndex,
                    KeysetChunker.encodePk(sink.values), stats.read, ChunkCheckpoint.STATUS_CHUNK));
            lastPk = sink.values;
            chunkIndex++;
            if (stats.read < chunkSize) {
                break;
            }
            ctx.requireLease();
        }

        // The load finished (or the table turned out empty): resume points served their purpose.
        checkpoints.deleteByProjectIdAndTableName(ctx.projectId(), table.getName());
        result.rowsWritten = totalRead;
        result.newCursorValue = fullLoadCursorTail(table, strategy, watermark, totalRead);
        return true;
    }

    /** Same cursor semantics as the classic full load, computed over the chunks' totals. */
    private String fullLoadCursorTail(TableMeta table, CursorStrategy strategy,
                                      String watermark, int totalRead) {
        return strategy.getKind() == CursorStrategy.Kind.FULL_COMPARE
                ? CursorStrategy.FULL_COMPARE_ROWCOUNT_PREFIX + totalRead
                : (watermark == null ? null : applySafetyLagIfKeyed(table, watermark));
    }

    /**
     * How many workers one table's chunked load may use. Stays at 1 (fully sequential, the
     * pre-parallelism behavior) unless the operator asked for more, the context can open extra
     * connections, and the key is a single integer column whose span can be split into
     * disjoint ranges. Clamped to 8 per the capacity plan.
     */
    int resolveParallelism(FullLoadRoute route, TableMeta table, SyncContext ctx) {
        // The factory already capped the project's value against the pool; a hand-built
        // context (tests, calls that bypass the factory) falls back to the global knob.
        int configured = ctx.getFullLoadParallelism() > 0
                ? ctx.getFullLoadParallelism() : properties.getFullLoadParallelism();
        if (configured <= 1) {
            return 1;
        }
        if (ctx.getConnectionProvider() == null || ctx.getSourceConfig() == null
                || ctx.getTargetConfig() == null) {
            return 1;
        }
        if (route != FullLoadRoute.KEYSET_NUMERIC || !hasIntegerPk(table)) {
            log.info("Parallel full load needs a single-column integer primary key;"
                    + " loading {} sequentially in chunks", table.getName());
            return 1;
        }
        return Math.min(configured, 8);
    }

    /** True when the single PK column is a plain integer type, so its span can be split. */
    private static boolean hasIntegerPk(TableMeta table) {
        return table.column(table.getPrimaryKeys().get(0)).map(c -> {
            int t = c.getJdbcType();
            if (t == Types.INTEGER || t == Types.BIGINT
                    || t == Types.SMALLINT || t == Types.TINYINT) {
                return true;
            }
            // Exact-numeral with scale zero and precision that fits a long is still an
            // integer domain — this is what a real Oracle NUMBER(18) primary key reports
            // (NUMERIC, digits 0), and without it Oracle sources could never range-split.
            // Scale > 0 and precision > 18 (NUMBER(19) can exceed Long.MAX_VALUE) are
            // refused: truncating either to a long span would break the disjointness
            // the parallel workers rely on.
            return (t == Types.NUMERIC || t == Types.DECIMAL)
                    && c.getDecimalDigits() != null && c.getDecimalDigits() == 0
                    && c.getSize() != null && c.getSize() <= 18;
        }).orElse(false);
    }

    /**
     * Parallel variant: the key span [MIN(pk), MAX(pk)] (within the watermark bound) is split
     * into disjoint ranges and one worker per range walks its slice with the same
     * chunk-and-checkpoint rhythm as the sequential path. Ranges complete independently and
     * out of order — correctness rests on the ranges being disjoint and every write being an
     * idempotent upsert, never on completion order.
     */
    private boolean parallelChunkedLoad(Connection sourceConn, Connection targetConn,
                                        TableMeta table, String targetTable,
                                        CursorStrategy strategy, String watermark,
                                        Object upperBound, SyncContext ctx,
                                        TableSyncResult result, List<ChunkCheckpoint> saved,
                                        int parallelism, boolean insertFirst) throws SQLException {
        String pk = table.getPrimaryKeys().get(0);
        long[] bounds = readPkBounds(sourceConn, table, pk, strategy, upperBound, ctx);
        if (bounds == null) {
            // Empty table (within the watermark bound): finalize like the sequential path
            // finalizes an empty load.
            checkpoints.deleteByProjectIdAndTableName(ctx.projectId(), table.getName());
            result.rowsWritten = 0;
            result.newCursorValue = fullLoadCursorTail(table, strategy, watermark, 0);
            return true;
        }
        List<long[]> ranges = splitRanges(bounds[0], bounds[1], parallelism);
        if (ranges == null) {
            // Key span too wide to split without overflow; sequential chunking spans anything.
            return sequentialChunkedLoad(sourceConn, targetConn, table, targetTable, strategy,
                    watermark, upperBound, ctx, result, saved, insertFirst);
        }
        // The split is recomputed on every attempt from the current parallelism and MIN/MAX.
        // A saved range index without its bounds cannot prove it covers the range it is now
        // mapped onto; trusting it can skip whole slices, so a mismatch is a full reload.
        List<ChunkCheckpoint> usable = validRangeCheckpoints(saved, ranges, table, ctx);
        log.info("Parallel full load of {}: key span [{}, {}] split into {} ranges ({} workers)",
                table.getName(), bounds[0], bounds[1], ranges.size(), parallelism);

        ExecutorService pool = Executors.newFixedThreadPool(ranges.size(), r -> {
            Thread t = new Thread(r, "full-load-" + table.getName());
            t.setDaemon(true);
            return t;
        });
        WriteStats total = new WriteStats();
        try {
            List<Future<WriteStats>> futures = new ArrayList<>(ranges.size());
            for (int i = 0; i < ranges.size(); i++) {
                final int rangeIndex = i;
                final long[] range = ranges.get(i);
                futures.add(pool.submit(() -> copyRange(table, targetTable, strategy, upperBound,
                        ctx, range, rangeIndex, resumePoint(usable, rangeIndex, table),
                        insertFirst)));
            }
            total = awaitWorkerStats(futures, pool);
        } finally {
            pool.shutdown();
        }

        checkpoints.deleteByProjectIdAndTableName(ctx.projectId(), table.getName());
        result.rowsWritten = total.read;
        result.rowsInserted += total.inserted;
        result.rowsUpdated += total.updated;
        result.rowsChangedUnclassified += total.unclassified;
        result.newCursorValue = fullLoadCursorTail(table, strategy, watermark, total.read);
        return true;
    }

    /**
     * Waits for every range worker and aggregates its stats.
     *
     * <p>An interrupt on the coordinating thread is a <em>stop request</em> (the CDC
     * consumer manager interrupts the thread it is stopping). It must reach the workers:
     * returning while they keep writing leaves the target changing after the cycle ended,
     * and the lease may already have been released. Workers are therefore interrupted and
     * given a bounded join, and the stop is rethrown as {@link StopRequestedException} with
     * the interrupt flag left set — the same contract the sequential load uses, so callers
     * can tell a legitimate stop from a failure.
     */
    private WriteStats awaitWorkerStats(List<Future<WriteStats>> futures, ExecutorService pool)
            throws SQLException {
        WriteStats total = new WriteStats();
        LockLostException leaseLost = null;
        SQLException failure = null;
        StopRequestedException stopped = null;
        for (Future<WriteStats> future : futures) {
            try {
                WriteStats w = future.get();
                total.read += w.read;
                total.inserted += w.inserted;
                total.updated += w.updated;
                total.unclassified += w.unclassified;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (stopped == null) {
                    stopped = new StopRequestedException(
                            "Parallel full load interrupted: stop requested");
                }
                // Every remaining get() would throw immediately on the set flag, and the
                // workers have not been told anything yet — stop them below.
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof LockLostException) {
                    if (leaseLost == null) {
                        leaseLost = (LockLostException) cause;
                    }
                } else if (cause instanceof StopRequestedException) {
                    if (stopped == null) {
                        stopped = (StopRequestedException) cause;
                    }
                } else if (failure == null) {
                    failure = cause instanceof SQLException ? (SQLException) cause
                            : new SQLException("Parallel full load failed: "
                                    + cause.getMessage(), cause);
                }
            }
        }
        if (stopped != null) {
            stopWorkers(pool);
            throw stopped;
        }
        if (leaseLost != null) {
            // Checkpoints stay: every committed chunk survives, and whoever holds the
            // lock next resumes instead of reloading the whole table.
            throw leaseLost;
        }
        if (failure != null) {
            // Same contract as a failed sequential load: checkpoints stay, the cursor is
            // not advanced, and the next cycle resumes where the ranges left off.
            throw failure;
        }
        return total;
    }

    /** Interrupts running range workers and waits a bounded time for them to unwind. */
    static void stopWorkers(ExecutorService pool) {
        List<Runnable> dropped = pool.shutdownNow();
        // The stop is delivered by interrupting this thread, so the flag is already set and
        // awaitTermination would throw on entry — skipping the join exactly when it matters
        // and letting workers keep committing after the load returned. Wait with the flag
        // cleared, then restore it so callers still see the stop.
        boolean interrupted = Thread.interrupted();
        boolean terminated = false;
        try {
            terminated = pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!terminated) {
            log.warn("Parallel full-load workers did not stop within 30s of a stop request;"
                    + " they abort at their next chunk boundary");
        }
        if (!dropped.isEmpty()) {
            log.debug("Dropped {} queued range task(s) on stop", dropped.size());
        }
    }

    /** One parallel worker: walks its own {@code (lo, hi]} key slice, checkpointing per chunk. */
    private WriteStats copyRange(TableMeta table, String targetTable, CursorStrategy strategy,
                                 Object upperBound, SyncContext ctx, long[] range, int rangeIndex,
                                 RangeResume resume, boolean insertFirst) throws SQLException {
        WriteStats totals = new WriteStats();
        if (resume.done) {
            return totals;
        }
        String pk = table.getPrimaryKeys().get(0);
        List<String> pkColumns = List.of(pk);
        int chunkSize = properties.getChunkSize();
        List<Object> lastPk = resume.boundary != null ? resume.boundary : List.of(range[0]);
        int chunkIndex = resume.nextChunkIndex;
        while (true) {
            // Same stop contract as the sequential path: a chunk boundary is a safe abort
            // point (the previous chunk is committed and checkpointed), and the interrupt
            // flag is left set so the caller can tell a stop from a failure.
            if (Thread.currentThread().isInterrupted()) {
                throw new StopRequestedException("Full load of " + table.getName()
                        + " range " + rangeIndex + " aborted at chunk " + chunkIndex
                        + ": stop requested (thread interrupted)");
            }
            List<Object> params = new ArrayList<>();
            String pageSql = KeysetChunker.pageSql(ctx.getSourceDialect(), ctx.getSourceSchema(),
                    table.getName(), pkColumns, lastPk, range[1], strategy, upperBound,
                    chunkSize, params);
            PkCursor sink = new PkCursor();
            WriteStats stats;
            // Borrow per chunk, not per range: a worker never holds pooled connections while
            // queued, so parallelism above the pool size degrades to waiting in line.
            try (Connection src = ctx.getConnectionProvider().open(ctx.getSourceConfig());
                 Connection tgt = ctx.getConnectionProvider().open(ctx.getTargetConfig())) {
                stats = copyRows(src, tgt, pageSql, params, table, targetTable, ctx,
                        pkColumns, sink, fullLoadBatchSize(ctx), fullLoadFetchSize(ctx),
                        insertFirst);
            }
            if (stats.read == 0) {
                break;
            }
            totals.read += stats.read;
            totals.inserted += stats.inserted;
            totals.updated += stats.updated;
            totals.unclassified += stats.unclassified;
            checkpoints.save(new ChunkCheckpoint(ctx.projectId(), table.getName(), rangeIndex,
                    chunkIndex++, KeysetChunker.encodePk(sink.values), stats.read,
                    ChunkCheckpoint.STATUS_CHUNK, range[0], range[1]));
            lastPk = sink.values;
            if (stats.read < chunkSize) {
                break;
            }
            ctx.requireLease();
        }
        // Marks the slice complete: a resumed load skips this range without reading it again.
        checkpoints.save(new ChunkCheckpoint(ctx.projectId(), table.getName(), rangeIndex,
                chunkIndex, null, 0, ChunkCheckpoint.STATUS_RANGE_DONE, range[0], range[1]));
        return totals;
    }

    /**
     * The inclusive {@code [MIN(pk), MAX(pk)]} span of the rows this load may see (the same
     * watermark bound the pages use), or null when that set is empty.
     */
    private long[] readPkBounds(Connection sourceConn, TableMeta table, String pkColumn,
                                CursorStrategy strategy, Object upperBound, SyncContext ctx)
            throws SQLException {
        SqlDialect dialect = ctx.getSourceDialect();
        String quotedPk = dialect.quoteIdentifier(pkColumn);
        StringBuilder sql = new StringBuilder("SELECT MIN(").append(quotedPk)
                .append("), MAX(").append(quotedPk).append(") FROM ")
                .append(dialect.qualify(ctx.getSourceSchema(), table.getName()));
        List<Object> params = new ArrayList<>();
        if (upperBound != null && strategy != null && strategy.getColumn() != null) {
            String cursor = KeysetChunker.sqlRef(dialect, strategy);
            sql.append(" WHERE (").append(cursor).append(" <= ?");
            if (!strategy.isPseudoColumn()) {
                sql.append(" OR ").append(cursor).append(" IS NULL");
            }
            sql.append(')');
            params.add(upperBound);
        }
        try (PreparedStatement st = sourceConn.prepareStatement(sql.toString())) {
            JdbcUtil.applyQueryTimeout(st, properties.getQueryTimeoutSeconds());
            for (int i = 0; i < params.size(); i++) {
                st.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long min = rs.getLong(1);
                if (rs.wasNull()) {
                    return null;
                }
                return new long[] {min, rs.getLong(2)};
            }
        }
    }

    /**
     * Splits the inclusive key span {@code [min, max]} into at most {@code parts} contiguous
     * {@code (lo, hi]} ranges covering exactly that span. Returns null when the span is too
     * wide to split without arithmetic overflow; the caller falls back to sequential chunking,
     * which handles any span. Ranges can come out sparse-keyed and uneven — that only affects
     * load balance, never correctness.
     */
    static List<long[]> splitRanges(long min, long max, int parts) {
        try {
            long lo = Math.subtractExact(min, 1);
            long total = Math.subtractExact(max, lo); // = max - min + 1
            if (total <= 0) {
                return null;
            }
            long width = parts <= 1 ? total
                    : Math.addExact(total, parts - 1) / parts; // ceil, overflow-checked
            List<long[]> ranges = new ArrayList<>();
            while (lo < max) {
                long hi = Math.min(Math.addExact(lo, width), max);
                ranges.add(new long[] {lo, hi});
                lo = hi;
            }
            return ranges.isEmpty() ? null : ranges;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** Where one range continues: after its newest completed chunk, or not at all. */
    private static final class RangeResume {
        private final boolean done;
        private final List<Object> boundary;
        private final int nextChunkIndex;

        private RangeResume(boolean done, List<Object> boundary, int nextChunkIndex) {
            this.done = done;
            this.boundary = boundary;
            this.nextChunkIndex = nextChunkIndex;
        }
    }

    /**
     * The checkpoints a resumed parallel load may trust, or an empty list after deleting
     * them. A range index is only meaningful together with the bounds it was recorded for:
     * the split depends on the current parallelism and the current MIN/MAX, so an index
     * alone can point at a different slice after either changes. Reloading instead is safe
     * because every write is an idempotent upsert.
     */
    private List<ChunkCheckpoint> validRangeCheckpoints(List<ChunkCheckpoint> saved,
                                                        List<long[]> ranges,
                                                        TableMeta table, SyncContext ctx) {
        if (saved.isEmpty()) {
            return saved;
        }
        boolean matches = saved.stream().allMatch(c -> {
            Integer index = c.getRangeIndex();
            if (index == null || index < 0 || index >= ranges.size()
                    || c.getRangeLower() == null || c.getRangeUpper() == null) {
                return false;
            }
            long[] range = ranges.get(index);
            return c.getRangeLower() == range[0] && c.getRangeUpper() == range[1];
        });
        if (matches) {
            return saved;
        }
        log.info("Discarding {} chunk checkpoint(s) for {}: the parallel key ranges changed"
                + " (parallelism or MIN/MAX); reloading the table", saved.size(), table.getName());
        checkpoints.deleteByProjectIdAndTableName(ctx.projectId(), table.getName());
        return List.of();
    }

    /** Reads a range's resume state from the table's checkpoints (newest chunk wins). */
    private static RangeResume resumePoint(List<ChunkCheckpoint> saved, int rangeIndex,
                                           TableMeta table) {
        ChunkCheckpoint latest = null;
        for (ChunkCheckpoint c : saved) {
            if (c.getRangeIndex() != null && c.getRangeIndex() == rangeIndex
                    && (latest == null || c.getChunkIndex() > latest.getChunkIndex())) {
                latest = c;
            }
        }
        if (latest == null) {
            return new RangeResume(false, null, 0);
        }
        if (ChunkCheckpoint.STATUS_RANGE_DONE.equals(latest.getStatus())) {
            return new RangeResume(true, null, latest.getChunkIndex() + 1);
        }
        // A temporal key round-trips through JSON as epoch millis; restore the column's JDBC
        // type so it can be rebound to a DATE/TIMESTAMP key column on resume.
        return new RangeResume(false,
                KeysetChunker.decodePk(latest.getLastPkJson(), pkJdbcTypes(table)),
                latest.getChunkIndex() + 1);
    }

    /** JDBC type of each primary-key column, in key order, for checkpoint decoding. */
    private static List<Integer> pkJdbcTypes(TableMeta table) {
        List<Integer> types = new ArrayList<>(table.getPrimaryKeys().size());
        for (String pk : table.getPrimaryKeys()) {
            types.add(table.column(pk).map(c -> c.getJdbcType()).orElse(Types.OTHER));
        }
        return types;
    }

    /**
     * Only a unique-key conflict counts: 23505 (SQL standard, DB2, H2, PostgreSQL) and 23000
     * (MySQL 1062, SQL Server 2627, Oracle ORA-00001). The rest of SQLState class 23 —
     * 23502 NOT NULL, 23503 foreign key, 23514 CHECK — is a real integrity failure that must
     * surface: the retried UPDATE would match no row and the row would vanish silently.
     */
    private boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && ("23505".equals(state) || "23000".equals(state))) {
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
     * {@link com.qqmu.jync.model.DatabaseType#MYSQL}'s URL.
     *
     * <p>Targets that cannot make that distinction are handled by
     * {@link com.qqmu.jync.model.DatabaseType#upsertCountsDistinguishInsertFromUpdate()}: their
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
                              SyncContext ctx, ThroughputMeter throughput) throws SQLException {
        try {
            int[] counts = ps.executeBatch();
            // A rewritten batch (MySQL with rewriteBatchedStatements) answers SUCCESS_NO_INFO
            // for every row once ANY row changed, so counting row by row would report the
            // whole batch. ROW_COUNT() still knows the statement's affected-rows total; it
            // must be read before the commit resets it.
            long affected = isRewrittenBatch(counts) && mysqlFamily(ctx)
                    ? readAffectedRowCount(conn) : -1L;
            int rewrittenChanged = rewrittenBatchChangedRows(counts, affected);
            throughput.recordBatch();
            commitAndRecord(conn, throughput);
            // The batch is a commit boundary and hence a safe renewal point: the rows it holds
            // are durable and idempotent. Stop now if the lease was lost, before the next batch.
            ctx.requireLease();
            boolean classify = ctx.getTargetType() != null
                    && ctx.getTargetType().upsertCountsDistinguishInsertFromUpdate();
            if (rewrittenChanged >= 0) {
                stats.unclassified += rewrittenChanged;
                return;
            }
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

    /** True for a MySQL-protocol target, whose rewritten batches hide per-row counts. */
    private static boolean mysqlFamily(SyncContext ctx) {
        return ctx.getTargetType() != null
                && ctx.getTargetType().getFamily() == com.qqmu.jync.model.DatabaseType.DialectFamily.MYSQL;
    }

    /**
     * Affected rows of the statement just executed on this connection, via MySQL's
     * {@code ROW_COUNT()}. Called before the commit, which would reset it. {@code -1} when the
     * question cannot be answered (a non-MySQL driver, a proxy, a failure), in which case the
     * caller keeps the conservative per-row accounting.
     */
    private long readAffectedRowCount(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ROW_COUNT()")) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (SQLException e) {
            log.debug("ROW_COUNT() unavailable after a batched write: {}", e.getMessage());
            return -1L;
        }
    }

    /**
     * Changed rows of a batch the driver compressed into one statement, bounded by the
     * statement's affected rows; {@code -1} when the counts are ordinary per-row counts (or
     * the total is unavailable) and the caller should classify row by row.
     *
     * <p>MySQL counts an inserted row as 1 and an updated row as 2, so the affected-rows total
     * is an upper bound of the changed rows and is capped by the batch size. It is never the
     * full batch unless the whole batch really moved.
     */
    static int rewrittenBatchChangedRows(int[] counts, long affectedRows) {
        if (affectedRows < 0 || !isRewrittenBatch(counts)) {
            return -1;
        }
        return (int) Math.min(counts.length, affectedRows);
    }

    /** True when every count is {@link Statement#SUCCESS_NO_INFO}: a compressed batch. */
    private static boolean isRewrittenBatch(int[] counts) {
        if (counts == null || counts.length == 0) {
            return false;
        }
        for (int count : counts) {
            if (count != Statement.SUCCESS_NO_INFO) {
                return false;
            }
        }
        return true;
    }

    /**
     * Commits and books the wait into the meter's commit share instead of the row-work share.
     * {@code COMMIT} blocks on durability — redo-log sync, a synchronous stand-by, slow
     * storage — which the row loop cannot influence and which must not be mistaken for slow
     * row processing when someone reads the throughput line.
     */
    private void commitAndRecord(Connection conn, ThroughputMeter throughput) throws SQLException {
        long start = System.nanoTime();
        conn.commit();
        throughput.recordCommit(System.nanoTime() - start);
    }

    /** Sums a batch's update counts, treating SUCCESS_NO_INFO as one affected row. */
    private int sumCounts(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
        }
        return total;
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
        // The target holds cleansed keys, so the source side must be transformed the same
        // way before comparing: otherwise every cleansed key looks "deleted at source" and
        // deletion sync wipes the whole table.
        ColumnCleanser cleanser = ColumnCleanser.forTable(ctx.getConfig(), table.getName());
        try (Statement st = sourceConn.createStatement()) {
            JdbcUtil.applyQueryTimeout(st, properties.getQueryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sourceSql)) {
                while (rs.next()) {
                    sourceKeys.add(cleansedKeyOf(cleanser, pk, rs));
                }
            }
        }

        List<List<Object>> toDelete = new ArrayList<>();
        String targetSql = "SELECT " + pk.stream().map(targetDialect::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b).orElseThrow()
                + " FROM " + targetDialect.qualify(ctx.getTargetSchema(), targetTable);
        try (Statement st = targetConn.createStatement()) {
            JdbcUtil.applyQueryTimeout(st, properties.getQueryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(targetSql)) {
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
        }

        if (toDelete.isEmpty()) {
            return 0;
        }

        boolean originalAutoCommit = targetConn.getAutoCommit();
        targetConn.setAutoCommit(false);
        int deleted = 0;
        String deleteSql = targetDialect.getDeleteByPkSql(ctx.getTargetSchema(), targetTable, pk);
        try (PreparedStatement ps = targetConn.prepareStatement(deleteSql)) {
            JdbcUtil.applyQueryTimeout(ps, properties.getQueryTimeoutSeconds());
            // 与写入路径同一套批处理：逐行 executeUpdate 意味着每删一行一次网络往返。
            int inBatch = 0;
            for (List<Object> keyValues : toDelete) {
                for (int i = 0; i < keyValues.size(); i++) {
                    ps.setObject(i + 1, keyValues.get(i));
                }
                // Keyed delete: repeating it is a no-op, so this stays idempotent.
                ps.addBatch();
                if (++inBatch >= ctx.getBatchSize()) {
                    deleted += sumCounts(ps.executeBatch());
                    targetConn.commit();
                    ctx.requireLease();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                deleted += sumCounts(ps.executeBatch());
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

    /** Source-side key, transformed by the project's column rules before comparison. */
    private String cleansedKeyOf(ColumnCleanser cleanser, List<String> pk, ResultSet rs)
            throws SQLException {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pk.size(); i++) {
            values.put(pk.get(i), rs.getObject(i + 1));
        }
        cleanser.apply(values);
        StringBuilder sb = new StringBuilder();
        for (String column : pk) {
            String part = keyPartOf(values.get(column));
            sb.append(part.length()).append(':').append(part).append('\u0001');
        }
        return sb.toString();
    }

    /** Builds a comparable composite-key string from the leading columns of a row. */
    private String keyOf(ResultSet rs, int columnCount) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= columnCount; i++) {
            // Length-prefix each part so ("a","bc") and ("ab","c") cannot collide.
            String part = keyPartOf(rs.getObject(i));
            sb.append(part.length()).append(':').append(part).append('\u0001');
        }
        return sb.toString();
    }

    /**
     * Renders one key value so the same logical value produces the same text on both sides.
     *
     * <p>Plain {@code String.valueOf} is not enough when the two drivers disagree about the
     * Java type of a column: a binary key's default toString is an identity hash that differs
     * on every read, a decimal can arrive at different scales ({@code 1.0} vs {@code 1.00}),
     * and a timestamp may surface as {@code java.sql.Timestamp} on one side and
     * {@code LocalDateTime} on the other. Any such mismatch paints every target row as
     * "deleted at source", and deletion sync then wipes the whole table.
     */
    static String keyPartOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            StringBuilder hex = new StringBuilder(((byte[]) value).length * 2);
            for (byte b : (byte[]) value) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        if (value instanceof Number) {
            // One canonical decimal text for every numeric flavor, so Long, BigInteger,
            // BigDecimal, Double and Float compare equal for the same value.
            try {
                return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException notFinite) {
                // NaN / Infinity have no decimal form; fall through to the raw text.
            }
        }
        if (value instanceof java.util.Date) {
            // Covers java.sql Date/Time/Timestamp: epoch millis, immune to formatting and
            // fractional-second differences between drivers.
            return Long.toString(((java.util.Date) value).getTime());
        }
        if (value instanceof java.time.Instant) {
            return Long.toString(((java.time.Instant) value).toEpochMilli());
        }
        if (value instanceof java.time.LocalDateTime) {
            return Long.toString(((java.time.LocalDateTime) value)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (value instanceof java.time.OffsetDateTime) {
            return Long.toString(((java.time.OffsetDateTime) value).toInstant().toEpochMilli());
        }
        if (value instanceof java.time.ZonedDateTime) {
            return Long.toString(((java.time.ZonedDateTime) value).toInstant().toEpochMilli());
        }
        if (value instanceof java.time.LocalDate) {
            return Long.toString(((java.time.LocalDate) value).toEpochDay());
        }
        if (value instanceof java.time.LocalTime) {
            return Long.toString(((java.time.LocalTime) value).toNanoOfDay());
        }
        return String.valueOf(value);
    }

    /**
     * Empties the target table, used before a requested full reload.
     *
     * @throws SQLException when neither TRUNCATE nor the DELETE fallback could empty the
     *         table. The caller must not load into it afterwards: rows the user asked to
     *         clear would silently survive among the freshly loaded ones.
     */
    public void truncateTarget(Connection targetConn, TableMeta table, SyncContext ctx)
            throws SQLException {
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
            // The fallback must report its own failure instead of dying at DEBUG level;
            // execute() rethrows a genuine error with the statement attached.
            executor.execute("DELETE FROM "
                    + ctx.getTargetDialect().qualify(ctx.getTargetSchema(), targetTable), false);
            log.info("Deleted all rows from target table {} before initial load", targetTable);
        }
        // The table is empty now, so any resume point describes rows that no longer exist.
        // Keeping them would let the next chunked load skip the ranges they mark as copied,
        // leaving permanent holes. Only reached after the truncate/delete succeeded — a failed
        // empty leaves the already-committed rows in place, and their checkpoints stay valid.
        if (checkpoints != null && ctx.getProject() != null) {
            checkpoints.deleteByProjectIdAndTableName(ctx.projectId(), table.getName());
        }
    }

    /**
     * Advances the target table's autoincrement generator past the ids just loaded.
     *
     * <p>Initial load inserts every id explicitly, which leaves a freshly created 12c identity
     * sequence (or the pre-12 sequence+trigger emulation) sitting at 1; the next application
     * insert that omits the id would then collide (ORA-00001). Oracle-family targets only —
     * other dialects return no reseed statements. Best effort: a failure is logged, never
     * thrown, since the loaded data itself is correct and the generator can be advanced manually.
     */
    public void reseedIdentityAfterInitialLoad(Connection targetConn, TableMeta table,
                                               SyncContext ctx) {
        if (ctx.getTargetType() == null
                || ctx.getTargetType().getFamily() != DatabaseType.DialectFamily.ORACLE) {
            return;
        }
        ColumnMeta identityColumn = singleAutoIncrementPrimaryKey(table);
        if (identityColumn == null) {
            return;
        }
        SqlDialect dialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(table.getName());

        long maxId;
        try {
            maxId = queryMaxValue(targetConn,
                    dialect.getMaxValueSql(ctx.getTargetSchema(), targetTable,
                            identityColumn.getName()));
        } catch (SQLException e) {
            log.warn("Could not read MAX({}) from {} to advance its identity generator: {}",
                    identityColumn.getName(), targetTable, e.getMessage());
            return;
        }
        // MAX never returns a row without a value, but an empty table yields SQL NULL: nothing
        // loaded, the new generator already starts at 1.
        if (maxId < 0) {
            return;
        }

        List<String> statements = dialect.getIdentityReseedDdl(
                ctx.getTargetSchema(), targetTable, identityColumn, maxId + 1);
        DdlExecutor executor = new DdlExecutor(targetConn);
        for (String ddl : statements) {
            try {
                executor.execute(ddl, false);
            } catch (SQLException e) {
                // The legacy first statement drops a sequence we own; if it is absent (a table
                // prepared manually), drop it from this attempt but still create/replace the rest.
                if (ddl.startsWith("DROP ")) {
                    log.debug("Best-effort DDL failed (continuing reseed): {}", ddl);
                    continue;
                }
                log.warn("Identity generator reseed for {} failed ({}); later inserts without an"
                        + " explicit id may collide until it is advanced manually.", targetTable,
                        e.getMessage());
                return;
            }
        }
        if (!statements.isEmpty()) {
            log.info("Advanced identity generator for {} past loaded id {}", targetTable, maxId);
        }
    }

    /** The single-column primary key when it is autoincrement, otherwise null. */
    private static ColumnMeta singleAutoIncrementPrimaryKey(TableMeta table) {
        List<String> primaryKeys = table.getPrimaryKeys();
        if (primaryKeys == null || primaryKeys.size() != 1 || table.getColumns() == null) {
            return null;
        }
        String keyColumn = primaryKeys.get(0);
        for (ColumnMeta column : table.getColumns()) {
            if (column.isAutoIncrement() && keyColumn != null
                    && keyColumn.equalsIgnoreCase(column.getName())) {
                return column;
            }
        }
        return null;
    }

    /** @return the column maximum, or -1 when the table is empty. */
    private static long queryMaxValue(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long value = rs.getLong(1);
                return rs.wasNull() ? -1L : value;
            }
            return -1L;
        }
    }
}
