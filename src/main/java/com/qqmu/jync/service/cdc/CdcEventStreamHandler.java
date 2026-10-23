package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.qqmu.jync.service.lob.LobSpillStore;

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

    /**
     * Message fragment of every fatal XA-ordering failure. {@link CdcFailureClassifier}
     * matches its lower-cased form so the project parks instead of hot-reconnecting: the
     * same stream determines the same violation, retrying can never succeed.
     */
    static final String XA_ORDERING_MARKER = "Unsupported XA transaction ordering";

    private final SyncConfig config;
    private final String sourceSchema;
    private final CdcTableMetaSupplier supplier;
    private final CdcRowMapper mapper;
    private final CdcRuntimeStats stats;
    private final Supplier<String> gtidSetSupplier;
    private final TransactionSink sink;

    /**
     * Spill store for the current transaction's oversized values; {@code null} disables
     * spilling entirely (streaming switched off, or a hand-built handler in a test).
     */
    private final LobSpillStore spillStore;
    private final long spillThresholdBytes;

    /**
     * Owns the files of the transaction currently buffered. Created lazily on the first
     * oversized value, closed on every path that clears the buffer.
     */
    private volatile CdcLobSpiller spiller;

    /** tableId -> TABLE_MAP payload; kept only to name tables in ignored-row diagnostics. */
    private final Map<Long, TableMapEventData> tableMaps = new LinkedHashMap<>();
    /** tableId -> binding; filtered ids (wrong schema, unselected, no PK) are absent. */
    private final Map<Long, CdcTableBinding> bindings = new LinkedHashMap<>();
    /** "database.table" -> current transaction's buffered changes. */
    private final Map<String, TableChange> buffered = new LinkedHashMap<>();
    private String currentFile;
    private long lastNextPosition;

    /**
     * Serial-XA state. MySQL 8.0.30+ writes a prepared branch (XA START, its rows, XA END
     * and XA PREPARE) to the binlog at PREPARE time, and the branch may stay prepared for
     * a long while — other sessions' transactions commit meanwhile and their events land
     * AFTER the branch's rows. The single position-bounded buffer above cannot attribute
     * rows to a branch, and advancing the position past a still-prepared branch would lose
     * its rows on restart. So the supported contract is one branch at a time with nothing
     * tracked committing meanwhile; any other shape parks the project (see
     * {@link #xaOrderingFailure}) rather than writing rows the source never committed.
     */
    private boolean xaBranchOpen;
    private boolean xaPrepared;
    /** Branch identifier captured at XA PREPARE; {@code null} when the SQL carried none. */
    private String xaPreparedXid;
    /** A tracked row was buffered after PREPARE: its branch can no longer be determined. */
    private boolean trackedRowsWhilePrepared;

    /** First failure of this connection's event handling; read by the consumer (ruling R1). */
    private final AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
    /** The client this handler must disconnect when event handling fails; set at wire time. */
    private volatile BinaryLogClient attachedClient;

    public CdcEventStreamHandler(SyncConfig config, String sourceSchema, CdcTableMetaSupplier supplier,
                                 CdcRowMapper mapper, CdcRuntimeStats stats,
                                 Supplier<String> gtidSetSupplier, TransactionSink sink) {
        this(config, sourceSchema, supplier, mapper, stats, gtidSetSupplier, sink, null, 0L);
    }

    /**
     * The spilling overload: values above {@code spillThresholdBytes} in the buffered
     * transaction are written to {@code spillStore} and replaced by replayable handles.
     */
    public CdcEventStreamHandler(SyncConfig config, String sourceSchema, CdcTableMetaSupplier supplier,
                                 CdcRowMapper mapper, CdcRuntimeStats stats,
                                 Supplier<String> gtidSetSupplier, TransactionSink sink,
                                 LobSpillStore spillStore, long spillThresholdBytes) {
        this.config = config;
        this.sourceSchema = sourceSchema;
        this.supplier = supplier;
        this.mapper = mapper;
        this.stats = stats;
        this.gtidSetSupplier = gtidSetSupplier;
        this.sink = sink;
        this.spillStore = spillStore;
        this.spillThresholdBytes = Math.max(0L, spillThresholdBytes);
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
                onQuery(header, event.getData());
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
                appendUpsert(change, mapper.mapRow(row, binding, stats, spiller()));
                noteTrackedRowsBuffered();
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
                // The before-image only feeds the PK comparison and a possible delete;
                // spilling its payload would write a file nobody ever reads.
                Map<String, Object> before = mapper.mapRow(entry.getKey(), binding, stats);
                Map<String, Object> after = mapper.mapRow(entry.getValue(), binding, stats, spiller());
                appendUpsert(change, after);
                if (CdcPrimaryKey.changed(before, after, binding)) {
                    appendDelete(change, before);
                }
                noteTrackedRowsBuffered();
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
                // A delete binds primary keys only: keep its row image out of the spiller.
                appendDelete(change, mapper.mapRow(row, binding, stats));
                noteTrackedRowsBuffered();
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
     * <p>XA transactions: verified against real MySQL 8.0.46 ROW binlog, the sequence is
     * Query(XA START) → rows → Query(XA END) → XA_prepare → Query(XA COMMIT), with NO
     * XID event afterwards — XA PREPARE already persisted the transaction, so XA COMMIT
     * is itself the commit point and must flush here. XA PREPARE alone must not (the
     * branch can still end in XA ROLLBACK). A later XID after the flush is an empty
     * no-op, which also covers binlog shapes that emit one (one-phase XA commit).
     *
     * <p>Only SERIAL XA is supported. The branch is written to the binlog at PREPARE, so a
     * long-prepared branch coexists with other sessions' commits in stream order; one
     * buffer cannot attribute those rows, and no safe position watermark exists without
     * per-branch buffering. A second branch, a tracked transaction or a DDL while a branch
     * is prepared therefore fails fatally ({@code XA_ORDERING}) instead of corrupting.
     */
    private void onQuery(EventHeaderV4 header, QueryEventData data) {
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
        if (upper.startsWith("XA ROLLBACK")) {
            // 事务已被源端回滚:它的行事件在 XA PREPARE 时刻已写入 binlog 并进了
            // buffer,而回滚后永远不会再来该事务的提交事件。不清空的话,这些已回滚的
            // 行会搭上「下一个正常事务」的提交边界泄漏到目标端(源端没有的行凭空出现)。
            rollbackXaBranch(normalized);
            return;
        }
        if (upper.startsWith("XA COMMIT")) {
            // 实测 MySQL 8 ROW binlog:两阶段 XA 在 XA COMMIT Query 事件处提交,
            // 其后没有 XID 事件。这就是提交边界,立刻 flush;PREPARE 已落 buffer
            // 的行此刻才在源库可见,此刻才允许进入目标库。
            commitXaBranch(header, normalized);
            return;
        }
        if (upper.startsWith("XA PREPARE")) {
            prepareXaBranch(normalized);
            return;
        }
        if (upper.startsWith("XA START")) {
            startXaBranch();
            return;
        }
        if (upper.startsWith("XA ") || upper.startsWith("XASTART") || "XASTATE".equals(upper)) {
            // XA END / SUSPEND / RECOVER — branch control, not DDL; rows stay buffered.
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
        if (xaPrepared) {
            // clearBuffer() below would drop the prepared branch's rows: its XA COMMIT
            // would then flush nothing and the source's committed rows would never reach
            // the target. DDL cannot be attributed to a table from the Query event, so
            // this is fatal rather than a guess.
            throw xaOrderingFailure("DDL was executed while branch " + describeBranch()
                    + " was prepared; clearing the buffer would discard its rows");
        }
        stats.addSkippedDdl(1);
        log.warn("Skipping DDL in CDC stream (database {}): {}", data.getDatabase(),
                normalized.length() > DDL_LOG_SQL_CHARS
                        ? normalized.substring(0, DDL_LOG_SQL_CHARS) + "..." : normalized);
        supplier.invalidate(null, null);
        clearBuffer();
    }

    /** Contract 7: one XID = one sink call = one position mark; empty buffers never flush. */
    private void onXid(EventHeaderV4 header) {
        long pos = header.getNextPosition() > 0 ? header.getNextPosition() : lastNextPosition;
        if (xaPrepared) {
            if (trackedRowsWhilePrepared) {
                throw xaOrderingFailure("a transaction committed on tracked tables while"
                        + " branch " + describeBranch() + " was prepared; the buffer can no"
                        + " longer tell its rows from the branch's");
            }
            // A transaction that touched nothing we track: nothing to apply, and the
            // position must NOT advance past the prepared branch's rows — a restart has to
            // replay them, or the branch's commit would find an empty stream.
            return;
        }
        flushTransaction(pos);
    }

    /**
     * XA START: a branch may only start when nothing else is in flight. MySQL writes each
     * branch's XA START when that branch prepares, so a second START while one is open or
     * prepared proves the source interleaves branches.
     */
    private void startXaBranch() {
        if (xaPrepared || xaBranchOpen) {
            throw xaOrderingFailure("a new XA branch starts while "
                    + (xaPrepared ? "branch " + describeBranch() + " is prepared"
                            : "another branch is still open")
                    + "; the buffered rows cannot be attributed to one branch");
        }
        xaBranchOpen = true;
    }

    /** XA PREPARE: the branch is now durably in the binlog but not yet committed. */
    private void prepareXaBranch(String sql) {
        if (xaPrepared) {
            throw xaOrderingFailure("a second XA branch is prepared while branch "
                    + describeBranch() + " is still prepared");
        }
        xaPrepared = true;
        xaPreparedXid = xaBranchId(sql);
        xaBranchOpen = false;
        trackedRowsWhilePrepared = false;
    }

    /** XA COMMIT: the branch's rows become visible now — this is the flush boundary. */
    private void commitXaBranch(EventHeaderV4 header, String sql) {
        requirePreparedBranch("XA COMMIT", sql);
        long pos = header.getNextPosition() > 0 ? header.getNextPosition() : lastNextPosition;
        // A failing flush throws with the buffer (and the pending state) retained: the
        // reconnect replays the branch instead of silently dropping it.
        flushTransaction(pos);
        clearXaState();
    }

    /** XA ROLLBACK: discard exactly what this branch buffered; it will never commit. */
    private void rollbackXaBranch(String sql) {
        requirePreparedBranch("XA ROLLBACK", sql);
        clearBuffer();
        clearXaState();
    }

    /**
     * The commit/rollback must name the branch that is prepared, when both identifiers are
     * parseable. A mismatch means the statement belongs to a different branch — one
     * prepared before this stream began, say — and treating it as this branch's boundary
     * would publish (or discard) the wrong rows.
     */
    private void requirePreparedBranch(String verb, String sql) {
        if (!xaPrepared) {
            return;                       // nothing of ours is pending: no boundary to claim
        }
        String xid = xaBranchId(sql);
        if (xid != null && xaPreparedXid != null && !xid.equals(xaPreparedXid)) {
            throw xaOrderingFailure(verb + " names branch " + xid + " while branch "
                    + xaPreparedXid + " is the one prepared");
        }
    }

    private void clearXaState() {
        xaPrepared = false;
        xaPreparedXid = null;
        xaBranchOpen = false;
        trackedRowsWhilePrepared = false;
    }

    private String describeBranch() {
        return xaPreparedXid == null ? "(unnamed)" : xaPreparedXid;
    }

    /**
     * The branch identifier of an XA statement — the literals after the verb
     * ({@code gtrid[, bqual[, formatID]]}), reassembled so {@code XA START 'g'} and
     * {@code XA COMMIT 'g'} compare equal whichever quote character the client used.
     * Trailing options ({@code ONE PHASE} on COMMIT, {@code JOIN} on START) are ignored.
     * Returns {@code null} when the statement carries no identifier ({@code XA RECOVER});
     * callers then skip the identity check instead of guessing.
     */
    static String xaBranchId(String sql) {
        int n = sql.length();
        int i = 2;                                              // past "XA"
        while (i < n && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        while (i < n && !Character.isWhitespace(sql.charAt(i))) {
            i++;                                                // past the verb
        }
        StringBuilder id = new StringBuilder();
        boolean empty = true;
        while (true) {
            while (i < n && (Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                StringBuilder part = new StringBuilder();
                i++;
                while (i < n) {
                    char ch = sql.charAt(i);
                    if (ch == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            part.append(quote);                 // doubled quote = literal
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    part.append(ch);
                    i++;
                }
                if (!empty) {
                    id.append(',');
                }
                // Canonical single quotes: MySQL treats 'g' and "g" as the same branch id,
                // so leaving the client's quote character in would make a PREPARE in one
                // spelling and a COMMIT in the other compare unequal — and that verdict
                // parks the project as XA_ORDERING. Doubling an embedded quote keeps the
                // joined form unambiguous: a gtrid containing ',' must not collide with a
                // multi-part xid.
                id.append('\'').append(part.toString().replace("'", "''")).append('\'');
                empty = false;
            } else if (Character.isDigit(c)
                    || (c == '-' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int start = i++;
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                if (!empty) {
                    id.append(',');
                }
                id.append(sql, start, i);
                empty = false;
            } else {
                break;                                          // ONE PHASE / JOIN / RESUME
            }
        }
        return empty ? null : id.toString();
    }

    /** Fatal, classified {@code XA_ORDERING}: parks the project instead of retrying. */
    private static IllegalStateException xaOrderingFailure(String detail) {
        return new IllegalStateException(XA_ORDERING_MARKER + ": " + detail
                + ". Jync supports serial two-phase XA branches only: one branch in flight,"
                + " and no other tracked transaction or DDL between XA PREPARE and XA COMMIT."
                + " Switch this project to polling capture, or commit XA branches serially.");
    }

    /**
     * Applies everything buffered since the last transaction boundary as one sink call at
     * {@code pos}. Shared by the XID event (ordinary transactions) and the XA COMMIT query
     * event (XA transactions, which ship no XID in two-phase form).
     */
    private void flushTransaction(long pos) {
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
            clearBuffer();
            return;
        }
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
        clearBuffer();
    }

    /** Lazily builds this transaction's spiller; a handler without a store pays nothing. */
    private CdcLobSpiller spiller() {
        CdcLobSpiller current = spiller;
        if (current != null) {
            return current;
        }
        if (spillStore == null || spillThresholdBytes <= 0) {
            return CdcLobSpiller.disabled();
        }
        current = new CdcLobSpiller(spillStore, spillThresholdBytes, config);
        spiller = current;
        return current;
    }

    /**
     * Marks that a tracked row entered the buffer after an XA branch was prepared: from
     * then on the buffer holds two transactions' rows with no way to attribute them.
     */
    private void noteTrackedRowsBuffered() {
        if (xaPrepared) {
            trackedRowsWhilePrepared = true;
        }
    }

    /** Drops the buffered transaction together with the files it spilled. */
    private void clearBuffer() {
        buffered.clear();
        releaseSpill();
    }

    /**
     * Releases the current transaction's spilled files. Called by the consumer after the
     * stream ends (a failed flush deliberately keeps them open while the buffer is still
     * retained, but that buffer dies with this handler). Idempotent.
     */
    public synchronized void releaseSpill() {
        CdcLobSpiller current = spiller;
        spiller = null;
        if (current != null) {
            current.close();
        }
    }

    private TableChange changeFor(CdcTableBinding binding) {
        String key = binding.getDatabase() + "." + binding.getTable();
        return buffered.computeIfAbsent(key, k -> new TableChange(binding));
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
     * <p>Key identity (including {@code byte[]} PK columns) is {@link CdcPrimaryKey}'s:
     * binary keys compare by content, never by array identity.
     */
    private static void appendUpsert(TableChange change, Map<String, Object> row) {
        List<Object> key = CdcPrimaryKey.of(row, change.binding);
        change.deletes.removeIf(buffered -> CdcPrimaryKey.of(buffered, change.binding).equals(key));
        change.upserts.add(row);
    }

    private static void appendDelete(TableChange change, Map<String, Object> row) {
        List<Object> key = CdcPrimaryKey.of(row, change.binding);
        change.upserts.removeIf(buffered -> CdcPrimaryKey.of(buffered, change.binding).equals(key));
        change.deletes.add(row);
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
