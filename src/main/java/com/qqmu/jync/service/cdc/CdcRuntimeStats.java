package com.qqmu.jync.service.cdc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters and status for one project's CDC pipeline, shared by the row mapper,
 * applier, stream handler and consumer (they run on different threads, so every
 * counter is atomic and every status field volatile). {@link #snapshot()} renders
 * the whole thing as a plain map the C3 status panel consumes directly.
 */
public final class CdcRuntimeStats {

    private final AtomicLong upserts = new AtomicLong();
    private final AtomicLong deletes = new AtomicLong();
    private final AtomicLong skippedDdl = new AtomicLong();
    private final AtomicLong skippedUnsupported = new AtomicLong();
    private final AtomicLong unmappable = new AtomicLong();
    private final AtomicLong zeroDateCoerced = new AtomicLong();

    /** Tables CDC cannot handle here (no PK, unsupported engine); ordered for stable UI display. */
    private final Set<String> unsupportedTables =
            new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    private volatile boolean connected;
    private volatile String lastError;

    private volatile String positionFile;
    private volatile long positionPos;
    private volatile String gtidSet;

    /** LogMiner watermark — populated only for LOGMINER_CDC projects; null otherwise. */
    private volatile Long positionScn;

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
