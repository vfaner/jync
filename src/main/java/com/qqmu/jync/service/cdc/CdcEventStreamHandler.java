package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.qqmu.jync.dto.SyncConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns the raw binlog event stream into buffered, per-table row changes and flushes
 * them at transaction (XID) boundaries (task book B5).
 *
 * <p>Buffering is per-transaction: one XID = one target commit = one position persist.
 * A failing flush keeps the buffer and surfaces the failure through the choke path below
 * — the consumer backs off and reconnects, and the whole transaction replays. Idempotent
 * upserts make the replay safe; that is the at-least-once contract end to end.
 *
 * <p><b>Choke path (controller ruling R1).</b> mysql-binlog-connector-java 0.41.5's
 * {@code BinaryLogClient.notifyEventListeners} SWALLOWS listener exceptions: it logs
 * "choked on" and continues the event loop, so a rethrow alone never drops the connection
 * — the consumer would sit silently hung at {@code connected=true} over a frozen position
 * while the event buffer grows. Therefore {@link #onEvent} wraps the whole event handling
 * in a catch-all: the failure is stored in an {@link AtomicReference} the consumer reads
 * after the stream ends ({@link #takeFailure()}), the attached client is disconnected from
 * the listener thread to end the stream immediately, and the throwable is rethrown so
 * direct callers (unit tests) still observe it.
 */
@Slf4j
public final class CdcEventStreamHandler implements BinaryLogClient.EventListener {

    /** Receives one binlog transaction's changes plus the position to persist right after. */
    public interface TransactionSink {
        void apply(List<TableChange> changes, CdcPositionMark mark) throws SQLException;
    }

    /** One source table's buffered changes inside the current transaction. */
    public static final class TableChange {
        public final CdcTableBinding binding;
        public final List<Map<String, Object>> upserts = new ArrayList<>();
        public final List<Map<String, Object>> deletes = new ArrayList<>();

        TableChange(CdcTableBinding binding) {
            this.binding = binding;
        }
    }

    private static final int DDL_LOG_SQL_CHARS = 200;

    private final SyncConfig config;
    private final String sourceSchema;
    private final CdcTableMetaSupplier supplier;
    private final CdcRowMapper mapper;
    private final CdcRuntimeStats stats;
    private final Supplier<String> gtidSetSupplier;
    private final TransactionSink sink;

    /** tableId -> TABLE_MAP payload; kept only to name tables in ignored-row diagnostics. */
    private final Map<Long, TableMapEventData> tableMaps = new LinkedHashMap<>();
    /** tableId -> binding; filtered ids (wrong schema, unselected, no PK) are absent. */
    private final Map<Long, CdcTableBinding> bindings = new LinkedHashMap<>();
    /** "database.table" -> current transaction's buffered changes. */
    private final Map<String, TableChange> buffered = new LinkedHashMap<>();
    private String currentFile;
    private long lastNextPosition;

    /** First failure of this connection's event handling; read by the consumer (ruling R1). */
    private final AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
    /** The client this handler must disconnect when event handling fails; set at wire time. */
    private volatile BinaryLogClient attachedClient;

    public CdcEventStreamHandler(SyncConfig config, String sourceSchema, CdcTableMetaSupplier supplier,
                                 CdcRowMapper mapper, CdcRuntimeStats stats,
                                 Supplier<String> gtidSetSupplier, TransactionSink sink) {
        this.config = config;
        this.sourceSchema = sourceSchema;
        this.supplier = supplier;
        this.mapper = mapper;
        this.stats = stats;
        this.gtidSetSupplier = gtidSetSupplier;
        this.sink = sink;
    }

    /**
     * Hands the handler the client it streams from, so a failed event can force the
     * disconnect itself (ruling R1 — the library swallows listener exceptions, so the
     * rethrow-to-drop-connection recovery cannot be relied on). Called once, at wire time,
     * before the handler is registered on that client.
     */
    public void attachClient(BinaryLogClient client) {
        this.attachedClient = client;
    }

    /**
     * Removes and returns the failure that ended this connection's stream, or {@code null}
     * when the handler never choked. Consulted by the consumer AFTER the stream ends and
     * BEFORE any communication-failure record: a handler-forced disconnect is voluntary,
     * so {@code onCommunicationFailure} never fires and this ref is the only carrier.
     */
    public Throwable takeFailure() {
        return listenerFailure.getAndSet(null);
    }

    @Override
    public void onEvent(Event event) {
        try {
            handleEvent(event);
        } catch (RuntimeException | Error t) {
            // R1 choke path: store, disconnect, rethrow. 0.41.5's notifyEventListeners logs
            // "choked on" and CONTINUES the loop on a listener exception — without our own
            // disconnect the consumer would hang silently at connected=true, position frozen.
            listenerFailure.compareAndSet(null, t);
            log.error("CDC event handling failed; dropping the binlog connection so the"
                    + " consumer can classify and replay from the last position", t);
            BinaryLogClient client = attachedClient;
            if (client != null) {
                try {
                    client.disconnect();
                } catch (IOException | RuntimeException e) {
                    // The original failure stays the one that counts; a broken disconnect
                    // only means the socket was already gone.
                    log.warn("Forced CDC disconnect failed: {}", e.getMessage());
                }
            }
            throw t;
        }
    }

    private void handleEvent(Event event) {
        EventHeaderV4 header = event.getHeader();
        if (header.getTimestamp() > 0) {
            stats.touchEvent(header.getTimestamp());
        }
        if (header.getNextPosition() > 0) {
            lastNextPosition = header.getNextPosition();
        }
        switch (header.getEventType()) {
            case TABLE_MAP:
                onTableMap(event.getData());
                break;
            case WRITE_ROWS: case EXT_WRITE_ROWS:
                onWrite(event.getData());
                break;
            case UPDATE_ROWS: case EXT_UPDATE_ROWS:
                onUpdate(event.getData());
                break;
            case DELETE_ROWS: case EXT_DELETE_ROWS:
                onDelete(event.getData());
                break;
            case QUERY:
                onQuery(event.getData());
                break;
            case ROTATE:
                RotateEventData rotate = event.getData();
                currentFile = rotate.getBinlogFilename();
                break;
            case XID:
                onXid(header);
                break;
            default:
                break; // GTID/HEARTBEAT/…: touchEvent above is all they need; no buffer semantics
        }
    }

    /** Contract 1: record the map, then filter — schema, selection, applicability (B4). */
    private void onTableMap(TableMapEventData data) {
        long tableId = data.getTableId();
        tableMaps.put(tableId, data);
        String database = data.getDatabase();
        String table = data.getTable();
        if (database == null || !database.equalsIgnoreCase(sourceSchema)) {
            bindings.remove(tableId);
            return;
        }
        if (!config.includesTable(table)) {
            bindings.remove(tableId);
            return;
        }
        CdcTableBinding binding = supplier.binding(database, table);
        if (binding == null || !binding.isApplicable()) {
            // No PK (or unreadable table): stays out of CDC on one-shot seeding (B4).
            // The stats set de-dupes, so repeat TABLE_MAPs report it only once.
            stats.addUnsupportedTable(database + "." + table);
            bindings.remove(tableId);
            return;
        }
        bindings.put(tableId, binding);
    }

    /** Contract 2: after-images buffer as upserts; one poison row never stalls the stream. */
    private void onWrite(WriteRowsEventData data) {
        CdcTableBinding binding = bindings.get(data.getTableId());
        if (binding == null) {
            logIgnoredRows(data.getTableId(), data.getRows().size());
            return;
        }
        TableChange change = changeFor(binding);
        for (Serializable[] row : data.getRows()) {
            try {
                appendUpsert(change, mapper.mapRow(row, binding, stats));
            } catch (CdcUnmappableValueException e) {
                countAndLogUnmappable(binding, "INSERT", e);
            }
        }
    }

    /** Contract 3 / Review Focus #2: after-image upserts; a moved PK also deletes the old row. */
    private void onUpdate(UpdateRowsEventData data) {
        CdcTableBinding binding = bindings.get(data.getTableId());
        if (binding == null) {
            logIgnoredRows(data.getTableId(), data.getRows().size());
            return;
        }
        TableChange change = changeFor(binding);
        for (Map.Entry<Serializable[], Serializable[]> entry : data.getRows()) {
            try {
                Map<String, Object> before = mapper.mapRow(entry.getKey(), binding, stats);
                Map<String, Object> after = mapper.mapRow(entry.getValue(), binding, stats);
                appendUpsert(change, after);
                if (primaryKeyChanged(before, after, binding)) {
                    appendDelete(change, before);
                }
            } catch (CdcUnmappableValueException e) {
                // Either image unmappable: skip the whole pair — upserting the after-image
                // without deleting the old key would orphan a row on a PK change.
                countAndLogUnmappable(binding, "UPDATE", e);
            }
        }
    }

    /** Contract 4: full row maps; the applier binds only the PK columns of each. */
    private void onDelete(DeleteRowsEventData data) {
        CdcTableBinding binding = bindings.get(data.getTableId());
        if (binding == null) {
            logIgnoredRows(data.getTableId(), data.getRows().size());
            return;
        }
        TableChange change = changeFor(binding);
        for (Serializable[] row : data.getRows()) {
            try {
                appendDelete(change, mapper.mapRow(row, binding, stats));
            } catch (CdcUnmappableValueException e) {
                countAndLogUnmappable(binding, "DELETE", e);
            }
        }
    }

    /**
     * Contract 5: BEGIN/COMMIT are no-ops; XA / SAVEPOINT / SET / CREATE TEMPORARY TABLE
     * are treated as no-ops as well (they are NOT DDL against tracked tables); anything
     * else is DDL — invalidate caches and discard the buffer.
     *
     * <p>XA transactions: {@code XA START} opens, DML rows buffer, {@code XA PREPARE}
     * marks the boundary (the subsequent {@code XA COMMIT} is a QUERY we treat as the
     * commit equivalent). Previously both XA PREPARE and XA COMMIT hit the "anything
     * non-BEGIN/COMMIT is DDL" branch: buffered rows were dropped, and the position
     * advanced on the next XID — silent data loss.
     */
    private void onQuery(QueryEventData data) {
        String sql = data.getSql();
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if ("BEGIN".equals(upper) || "COMMIT".equals(upper) || "ROLLBACK".equals(upper)) {
            return; // transaction control, not DDL: the buffer stays
        }
        if (upper.startsWith("XA ") || upper.startsWith("XASTART") || "XASTATE".equals(upper)) {
            // XA START / XA END / XA PREPARE / XA COMMIT / XA ROLLBACK — transaction
            // control, not DDL. Rows buffered between XA START and XA PREPARE must stay
            // in the buffer until the XID event; XA PREPARE/COMMIT do not flush on their
            // own (MySQL binlog emits an XID after XA COMMIT for the prepared branch).
            return;
        }
        if (upper.startsWith("SAVEPOINT ") || upper.startsWith("RELEASE SAVEPOINT ")
                || upper.startsWith("ROLLBACK TO SAVEPOINT ")
                || upper.startsWith("ROLLBACK TO ")) {
            return; // sub-transaction control: buffer semantics stay
        }
        if (upper.startsWith("SET ") || upper.startsWith("SET@@")) {
            return; // session variable change: no DDL, no buffer impact
        }
        if (upper.startsWith("CREATE TEMPORARY TABLE")
                || upper.startsWith("CREATE/*TEMP*/TABLE")
                || upper.startsWith("DROP TEMPORARY TABLE")) {
            return; // TEMPORARY tables are not tracked by CDC
        }
        stats.addSkippedDdl(1);
        log.warn("Skipping DDL in CDC stream (database {}): {}", data.getDatabase(),
                normalized.length() > DDL_LOG_SQL_CHARS
                        ? normalized.substring(0, DDL_LOG_SQL_CHARS) + "..." : normalized);
        supplier.invalidate(null, null);
        buffered.clear();
    }

    /** Contract 7: one XID = one sink call = one position mark; empty buffers never flush. */
    private void onXid(EventHeaderV4 header) {
        List<TableChange> changes = new ArrayList<>();
        for (TableChange change : buffered.values()) {
            if (!change.upserts.isEmpty() || !change.deletes.isEmpty()) {
                changes.add(change);
            }
        }
        if (changes.isEmpty()) {
            // The transaction touched nothing we track (other schemas, unselected or
            // unsupported tables, only unmappable rows): never call the sink — an empty
            // apply would push the position forward with no data behind it.
            buffered.clear();
            return;
        }
        long pos = header.getNextPosition() > 0 ? header.getNextPosition() : lastNextPosition;
        CdcPositionMark mark = new CdcPositionMark(currentFile, pos, gtidSetSupplier.get());
        try {
            sink.apply(changes, mark);
        } catch (SQLException e) {
            log.error("CDC flush failed at {}:{}", mark.getFile(), mark.getPos(), e);
            // Keep the buffer, do not advance the position, rethrow: the onEvent choke path
            // (ruling R1) stores this failure and forces the disconnect — the consumer
            // classifies, backs off, reconnects, and the whole transaction replays —
            // idempotent upserts absorb it (at-least-once).
            throw new RuntimeException("CDC transaction flush failed at "
                    + mark.getFile() + ":" + mark.getPos(), e);
        }
        buffered.clear();
    }

    private TableChange changeFor(CdcTableBinding binding) {
        String key = binding.getDatabase() + "." + binding.getTable();
        return buffered.computeIfAbsent(key, k -> new TableChange(binding));
    }

    private static boolean primaryKeyChanged(Map<String, Object> before, Map<String, Object> after,
                                             CdcTableBinding binding) {
        for (String pk : binding.pkNames()) {
            if (!Objects.equals(before.get(pk), after.get(pk))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same-PK last-op-wins folding within one transaction (FIX C). The sink applies
     * upserts BEFORE deletes — that order is what makes PK chains (A→B, B→C) safe — so an
     * unfilled buffer holding both an upsert and a delete for the SAME primary key (a
     * row-based {@code REPLACE INTO} emits Delete_rows + Write_rows in one XID) would
     * upsert the row and then delete it: silent row loss. Folding rule: appending an
     * upsert drops any buffered delete of the same PK; appending a delete drops any
     * buffered upsert of the same PK.
     *
     * <p>Cost: one O(n) scan of the opposite list per append — fine for typical small CDC
     * transactions; a rare bulk {@code REPLACE ... SELECT} pays a one-time quadratic.
     *
     * <p>Limitation: PK columns that map to {@code byte[]} never fold (list equality uses
     * element {@code equals}, arrays compare by identity) — such tables degrade to the
     * previous naive buffering, which is acceptable: correctness then matches pre-fix
     * behavior rather than regressing.
     */
    private static void appendUpsert(TableChange change, Map<String, Object> row) {
        List<Object> key = pkKey(row, change.binding);
        change.deletes.removeIf(buffered -> pkKey(buffered, change.binding).equals(key));
        change.upserts.add(row);
    }

    private static void appendDelete(TableChange change, Map<String, Object> row) {
        List<Object> key = pkKey(row, change.binding);
        change.upserts.removeIf(buffered -> pkKey(buffered, change.binding).equals(key));
        change.deletes.add(row);
    }

    /** The row's primary-key tuple, extracted exactly like {@link #primaryKeyChanged} reads it. */
    private static List<Object> pkKey(Map<String, Object> row, CdcTableBinding binding) {
        List<Object> key = new ArrayList<>(binding.pkNames().size());
        for (String pk : binding.pkNames()) {
            key.add(row.get(pk));
        }
        return key;
    }

    private void countAndLogUnmappable(CdcTableBinding binding, String operation,
                                       CdcUnmappableValueException e) {
        stats.addUnmappable(1);
        log.error("Skipping {} row on {}.{}: unmappable column '{}' (type {})",
                operation, binding.getDatabase(), binding.getTable(), e.getColumn(), e.getTypeName(), e);
    }

    private void logIgnoredRows(long tableId, int rowCount) {
        if (log.isDebugEnabled()) {
            TableMapEventData map = tableMaps.get(tableId);
            log.debug("Ignoring {} row(s) for tableId {} ({}): filtered or unsupported",
                    rowCount, tableId,
                    map == null ? "unknown table" : map.getDatabase() + "." + map.getTable());
        }
    }
}
