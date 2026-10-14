package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Counters and status for one project's CDC pipeline, shared by the row mapper,
 * applier, stream handler and consumer (they run on different threads, so every
 * counter is atomic and every status field volatile). {@link #snapshot()} renders
 * the whole thing as a plain map the C3 status panel consumes directly.
 */
public final class CdcRuntimeStats {

    /** One table's aggregated change counts since its log window opened. */
    public static final class TableChange {
        private final String table;
        private final long upserts;
        private final long deletes;

        TableChange(String table, long upserts, long deletes) {
            this.table = table;
            this.upserts = upserts;
            this.deletes = deletes;
        }

        public String table() {
            return table;
        }

        public long upserts() {
            return upserts;
        }

        public long deletes() {
            return deletes;
        }
    }

    /** Per-table aggregation window: counts accumulate until the logger drains it. */
    private static final class Window {
        private final long openedAtMillis;
        private long upserts;
        private long deletes;

        Window(long openedAtMillis) {
            this.openedAtMillis = openedAtMillis;
        }
    }

    private final AtomicLong upserts = new AtomicLong();
    private final AtomicLong deletes = new AtomicLong();
    private final AtomicLong skippedDdl = new AtomicLong();
    private final AtomicLong skippedUnsupported = new AtomicLong();
    private final AtomicLong unmappable = new AtomicLong();
    private final AtomicLong zeroDateCoerced = new AtomicLong();

    /** Tables CDC cannot handle here (no PK, unsupported engine); ordered for stable UI display. */
    private final Set<String> unsupportedTables =
            new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * Open per-table log windows, keyed case-insensitively (MySQL/PG fold name case;
     * Oracle's data dictionary spells upper-case). Insertion/removal and accumulation use
     * {@link ConcurrentMap#compute} on the same key, so a drain never loses concurrent rows —
     * rows arriving after a window is handed off simply open the next window.
     */
    private final ConcurrentMap<String, Window> tableWindows =
            new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Clock for window-age decisions; injectable in tests, wall time in production. */
    private final LongSupplier clockMillis;

    public CdcRuntimeStats() {
        this(System::currentTimeMillis);
    }

    CdcRuntimeStats(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    private volatile boolean connected;
    private volatile String lastError;

    private volatile String positionFile;
    private volatile long positionPos;
    private volatile String gtidSet;

    /** LogMiner watermark — populated only for LOGMINER_CDC projects; null otherwise. */
    private volatile Long positionScn;

    /** LSN watermark — populated only for PG_LOGICAL_CDC / SQLSERVER_CDC; null otherwise. */
    private volatile String positionLsn;

    private volatile long lastEventEpochMillis;
    private volatile long startedAtMillis;

    // —— counters ——

    public void addUpserts(long n) {
        upserts.addAndGet(n);
    }

    public void addDeletes(long n) {
        deletes.addAndGet(n);
    }

    public void addSkippedDdl(long n) {
        skippedDdl.addAndGet(n);
    }

    public void addSkippedUnsupported(long n) {
        skippedUnsupported.addAndGet(n);
    }

    public void addUnmappable(long n) {
        unmappable.addAndGet(n);
    }

    public void addZeroDateCoerced(long n) {
        zeroDateCoerced.addAndGet(n);
    }

    /** Rows actually written downstream: upserts plus deletes. */
    public long getRowsApplied() {
        return upserts.get() + deletes.get();
    }

    // —— per-table aggregation windows for the change log ——

    public void addTableUpserts(String table, long n) {
        addToWindow(table, n, true);
    }

    public void addTableDeletes(String table, long n) {
        addToWindow(table, n, false);
    }

    private void addToWindow(String table, long n, boolean isUpsert) {
        if (table == null || n <= 0) {
            return;
        }
        // compute() is exclusive per key with drain's removeIf path: a concurrent drain
        // either hands off the old window first (these rows open a fresh one) or waits,
        // but rows can never land in a window that was already removed.
        tableWindows.compute(table, (key, existing) -> {
            Window window = existing != null ? existing : new Window(clockMillis.getAsLong());
            if (isUpsert) {
                window.upserts += n;
            } else {
                window.deletes += n;
            }
            return window;
        });
        // The status-panel total is the sum of every per-table window (plus rows for
        // keyless/unsupported streams that the applier still counts globally).
        if (isUpsert) {
            upserts.addAndGet(n);
        } else {
            deletes.addAndGet(n);
        }
    }

    /**
     * Atomically removes and returns windows that have been open at least {@code maxAgeMs}
     * by {@code nowMillis}; map order is case-insensitive, so the result is stable.
     */
    List<TableChange> drainDueWindows(long maxAgeMs, long nowMillis) {
        List<TableChange> due = new ArrayList<>();
        tableWindows.entrySet().removeIf(entry -> {
            Window window = entry.getValue();
            if (nowMillis - window.openedAtMillis < maxAgeMs) {
                return false;
            }
            if (window.upserts > 0 || window.deletes > 0) {
                due.add(new TableChange(entry.getKey(), window.upserts, window.deletes));
            }
            return true;
        });
        return due;
    }

    /** Removes and returns every open window (consumer shutdown: nothing stays unlogged). */
    public List<TableChange> drainAllWindows() {
        List<TableChange> all = new ArrayList<>();
        tableWindows.entrySet().removeIf(entry -> {
            Window window = entry.getValue();
            if (window.upserts > 0 || window.deletes > 0) {
                all.add(new TableChange(entry.getKey(), window.upserts, window.deletes));
            }
            return true;
        });
        return all;
    }

    /**
     * Human-readable current watermark for log detail text, in source-engine order:
     * binlog file:pos, then LogMiner SCN, then PG/SQLServer LSN; null before any position.
     */
    public String positionLabel() {
        if (positionFile != null) {
            return positionFile + ":" + positionPos;
        }
        if (positionScn != null) {
            return "SCN " + positionScn;
        }
        if (positionLsn != null) {
            return "LSN " + positionLsn;
        }
        return null;
    }

    // —— connection state ——

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getLastError() {
        return lastError;
    }

    // —— position / lag ——

    public void recordPosition(String file, long pos, String gtidSet) {
        this.positionFile = file;
        this.positionPos = pos;
        this.gtidSet = gtidSet;
    }

    public String getPositionFile() {
        return positionFile;
    }

    public long getPositionPos() {
        return positionPos;
    }

    public String getGtidSet() {
        return gtidSet;
    }

    /** LogMiner: record the current SCN watermark shown on the status panel. */
    public void recordPositionScn(long scn) {
        this.positionScn = scn;
    }

    public Long getPositionScn() {
        return positionScn;
    }

    /** PG/SQLServer: record the current LSN watermark shown on the status panel. */
    public void recordPositionLsn(String lsn) {
        this.positionLsn = lsn;
    }

    public String getPositionLsn() {
        return positionLsn;
    }

    public void touchEvent(long epochMillis) {
        this.lastEventEpochMillis = epochMillis;
    }

    /** Seconds since the last event; -1 while disconnected or before the first event. */
    public long lagSeconds() {
        long last = lastEventEpochMillis;
        if (!connected || last <= 0) {
            return -1L;
        }
        return Math.max(0L, (System.currentTimeMillis() - last) / 1000L);
    }

    // —— throughput ——

    public void markStarted() {
        this.startedAtMillis = System.currentTimeMillis();
    }

    /** Rows applied per second since {@link #markStarted()}; 0.0 during the first second. */
    public double eventsPerSecond() {
        long started = startedAtMillis;
        if (started <= 0) {
            return 0.0;
        }
        long elapsedMillis = System.currentTimeMillis() - started;
        if (elapsedMillis < 1000L) {
            return 0.0;
        }
        double rate = getRowsApplied() * 1000.0 / elapsedMillis;
        return Math.round(rate * 10) / 10.0;
    }

    // —— unsupported tables ——

    public void addUnsupportedTable(String table) {
        unsupportedTables.add(table);
    }

    public Set<String> unsupportedTables() {
        return Collections.unmodifiableSet(unsupportedTables);
    }

    // —— snapshot ——

    /** Everything the status panel shows, in display order. */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("connected", connected);
        snapshot.put("lastError", lastError);
        snapshot.put("positionFile", positionFile);
        snapshot.put("positionPos", positionPos);
        snapshot.put("gtidSet", gtidSet);
        snapshot.put("positionScn", positionScn);
        snapshot.put("positionLsn", positionLsn);
        snapshot.put("lagSeconds", lagSeconds());
        snapshot.put("eventsPerSecond", eventsPerSecond());
        snapshot.put("rowsApplied", getRowsApplied());
        snapshot.put("skippedDdl", skippedDdl.get());
        snapshot.put("skippedUnsupported", skippedUnsupported.get());
        snapshot.put("unmappable", unmappable.get());
        snapshot.put("zeroDateCoerced", zeroDateCoerced.get());
        snapshot.put("unsupportedTables", new LinkedHashSet<>(unsupportedTables));
        return snapshot;
    }
}
