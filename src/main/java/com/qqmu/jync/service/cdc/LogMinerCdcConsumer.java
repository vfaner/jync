package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Long-lived Oracle LogMiner CDC pipeline (v1.6.0). Lifecycle mirrors {@link CdcConsumer}
 * one-for-one: baseline gate → endless mine/parse/apply loop with lease renewal at the top,
 * requestStop as the quiet exit, fatal-stop on PURGED/AUTH/PARSE_STRIKE_OUT, catch-all
 * quiet-vs-park distinction, finally-block lease release + INFO stop line.
 *
 * <p>Position discipline (data-first): one CSCN = one target commit → lease re-check →
 * {@code positionStore.advanceScn}. A crash between commit and advanceScn replays the whole
 * transaction; upserts are idempotent.
 *
 * <p>Differences from the binlog consumer are all forced by the source:
 * <ul>
 *   <li>position is a single SCN (no binlog file/pos/gtid triple);</li>
 *   <li>each mine cycle opens a fresh {@link LogMinerSession} (logfiles rotate, online redo
 *       switches); an ORA-01291 mid-mine is classified PURGED and fatally parks;</li>
 *   <li>transactions are batched by CSCN change (COMMITTED_DATA_ONLY guarantees one XID per
 *       CSCN);</li>
 *   <li>rows arrive as SQL_REDO text parsed by {@link SqlRedoParser}; unsupported statements
 *       are skipped + counted, never guessed.</li>
 * </ul>
 */
@Slf4j
public final class LogMinerCdcConsumer implements ManagedCdcConsumer {

    /** DDL SQL_REDO truncation for WARN logs — same cap as the binlog handler (200 chars). */
    private static final int DDL_LOG_SQL_CHARS = 200;
    /** Parse strike-out: same threshold as {@link CdcConsumer} (5 consecutive poison rows). */
    static final int MAX_PARSE_FAILURES = 5;

    private final Project project;
    private final SyncLockService.LockHandle lock;
    private final SyncContext ctx;
    private final CdcPositionStore positionStore;
    private final CdcBaselineService baselineService;
    private final SyncTaskStore taskStore;
    private final SyncStateWriter stateWriter;
    private final DataSourceManager dataSourceManager;
    private final CdcRuntimeStats stats;
    private final SyncProperties properties;
    private final LogMinerSessionFactory sessionFactory;

    private final CdcEventApplier applier;
    private final SqlRedoParser parser = new SqlRedoParser();
    private final BindingSupplier supplier = new BindingSupplier();

    private volatile boolean running = true;
    private volatile Connection currentSource;
    private volatile LogMinerSession currentSession;
    /**
     * CON_ID of the connection's current container. On a non-CDB this is 0/1 and the
     * SRC_CON_ID filter below is a no-op. On a CDB mined from CDB$ROOT, only rows with
     * this con_id are in scope; rows from other PDBs are discarded.
     */
    private volatile int ownConId = -1;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicInteger parseFailures = new AtomicInteger();
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    public LogMinerCdcConsumer(Project project, SyncLockService.LockHandle lock,
                               SyncContext ctx, CdcPositionStore positionStore,
                               CdcBaselineService baselineService, SyncTaskStore taskStore,
                               SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                               CdcRuntimeStats stats, SyncProperties properties,
                               LogMinerSessionFactory sessionFactory) {
        this.project = project;
        this.lock = lock;
        this.ctx = ctx;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.stateWriter = stateWriter;
        this.dataSourceManager = dataSourceManager;
        this.stats = stats;
        this.properties = properties;
        this.sessionFactory = sessionFactory;
        this.applier = new CdcEventApplier(ctx, stats, properties.getQueryTimeoutSeconds());
    }

    @Override
    public void run() {
        long projectId = project.getId();
        taskStore.markCdcRunning(projectId);
        try {
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !Boolean.TRUE.equals(position.isBaselineDone())) {
                baselineService.runBaseline(ctx, lock::renew);
                position = positionStore.find(projectId).orElse(null);
            }
            if (position == null || position.getScn() == null) {
                throw new IllegalStateException("LogMiner baseline completed without a"
                        + " recorded SCN anchor; refusing to mine from an unknown position");
            }
            long lastScn = position.getScn();
            backoff = new CdcReconnectBackoff(
                    properties.getCdc().getReconnectBackoffMs(),
                    properties.getCdc().getReconnectBackoffMaxMs());
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!lock.renew()) {
                    log.warn("LogMiner consumer for project {} stopping: lease lost (another"
                            + " node may have taken over)", projectId);
                    stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                            ChangeType.INFO, "LogMiner consumer stopped: lease lost", true, 0L);
                    break;
                }
                lastFailure.set(null);
                connectedAtNs.set(0L);
                List<LogMinerSession.Row> drained;
                try {
                    Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig());
                    currentSource = conn;
                    if (ownConId < 0) {
                        ownConId = readOwnConId(conn);
                    }
                    int batchSize = (int) Math.min(Integer.MAX_VALUE,
                            properties.getCdc().getLogminer().getBatchSize());
                    LogMinerSession session = sessionFactory.open(conn, lastScn, batchSize);
                    currentSession = session;
                    stats.setConnected(true);
                    stats.markStarted();
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    drained = drain(session);
                    session.close();
                } catch (Throwable e) {
                    lastFailure.compareAndSet(null, e);
                    drained = Collections.emptyList();
                } finally {
                    closeQuietly(currentSession);
                    closeQuietly(currentSource);
                    currentSession = null;
                    currentSource = null;
                    stats.setConnected(false);
                }
                if (!running) {
                    break;
                }
                Throwable failure = lastFailure.get();
                CdcFailureClassifier.Kind kind = failure == null
                        ? CdcFailureClassifier.Kind.TRANSIENT
                        : CdcFailureClassifier.classify(failure);
                if (kind != CdcFailureClassifier.Kind.TRANSIENT || parseStrikeOut()) {
                    fatalStop(projectId, failure, kind);
                    break;
                }
                // R3b mirror: reset the backoff only when the session demonstrably lived.
                long connectedAt = connectedAtNs.get();
                long dwellMs = connectedAt == 0L
                        ? 0L : (System.nanoTime() - connectedAt) / 1_000_000L;
                if (dwellMs >= CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS) {
                    backoff.reset();
                }
                long delay = drained.isEmpty()
                        ? properties.getCdc().getLogminer().getIdlePollMs()
                        : backoff.nextDelayMs();
                stats.setLastError(failure == null
                        ? "mining cycle drained" : CdcFailureClassifier.describe(failure));
                log.warn("LogMiner cycle for project {} ended ({}); next cycle in {} ms",
                        projectId, failure == null ? "no failure recorded"
                                : CdcFailureClassifier.describe(failure), delay);
                if (!sleepWhileRunning(delay)) {
                    break;
                }
            }
        } catch (RuntimeException | Error e) {
            if (!running || Thread.currentThread().isInterrupted()) {
                log.info("LogMiner consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else {
                String detail = CdcFailureClassifier.describe(e);
                stats.setLastError(detail);
                taskStore.recordCdcFailure(projectId, detail);
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.ERROR, "LogMiner consumer failed: " + detail, false, 0L);
                log.error("LogMiner consumer for project {} failed", projectId, e);
            }
        } finally {
            closeQuietly(currentSession);
            closeQuietly(currentSource);
            lock.close();
            stats.setConnected(false);
            try {
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.INFO, "LogMiner consumer stopped", true, 0L);
            } catch (RuntimeException e) {
                log.warn("Could not record the LogMiner stop log for project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }

    /**
     * Read the connection's current container ID (V$DATABASE.OPEND_MODES context). On a
     * non-CDB this returns 0; SRC_CON_ID filtering then passes every row.
     */
    private int readOwnConId(Connection conn) {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV', 'CON_ID') FROM DUAL")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            log.debug("Could not read CON_ID (non-CDB?): {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Drain one session: accumulate rows per transaction key (CSCN) and flush on CSCN change
     * (target commit → lease renew → advanceScn). Empty drain means the session found nothing
     * past lastScn — the caller idles.
     */
    private List<LogMinerSession.Row> drain(LogMinerSession session) throws Exception {
        long projectId = project.getId();
        long currentCsn = -1L;
        Map<CdcTableBinding, List<Map<String, Object>>> upserts = new LinkedHashMap<>();
        Map<CdcTableBinding, List<Map<String, Object>>> deletes = new LinkedHashMap<>();
        List<LogMinerSession.Row> all = new ArrayList<>();
        while (running && !Thread.currentThread().isInterrupted()) {
            List<LogMinerSession.Row> batch = session.nextBatch();
            if (batch.isEmpty()) {
                break;
            }
            for (LogMinerSession.Row row : batch) {
                // CDB isolation: on a CDB mined from CDB$ROOT, rows from other PDBs carry
                // a different SRC_CON_ID. Drop them here so cross-PDB same-name tables
                // cannot leak through the owner+table match below. On non-CDB ownConId=0
                // and every row passes (SRC_CON_ID is NULL/0 in V$LOGMNR_CONTENTS).
                if (ownConId > 0 && row.srcConId > 0 && row.srcConId != ownConId) {
                    continue;
                }
                if (!"DDL".equals(row.operation)
                        && !"INSERT".equals(row.operation)
                        && !"UPDATE".equals(row.operation)
                        && !"DELETE".equals(row.operation)) {
                    continue;
                }
                if (!matchesProjectTable(row.segOwner, row.tableName)) {
                    continue;
                }
                if ("DDL".equals(row.operation)) {
                    stats.addSkippedDdl(1);
                    String sql = row.sqlRedo == null ? "" : row.sqlRedo;
                    log.warn("Skipping DDL in LogMiner stream ({}): {}", row.segOwner,
                            sql.length() > DDL_LOG_SQL_CHARS
                                    ? sql.substring(0, DDL_LOG_SQL_CHARS) + "..." : sql);
                    // Invalidate the cached binding for this table so the next DML event
                    // re-reads the source column list. Without this, a source-side ADD
                    // COLUMN would be invisible to the parser until consumer restart.
                    supplier.invalidate(row.segOwner, row.tableName);
                    continue;
                }
                CdcTableBinding binding = supplier.binding(row.segOwner, row.tableName);
                if (binding == null || !binding.isApplicable()) {
                    stats.addUnsupportedTable(row.segOwner + "." + row.tableName);
                    continue;
                }
                SqlRedoParser.Op op;
                switch (row.operation) {
                    case "INSERT": op = SqlRedoParser.Op.INSERT; break;
                    case "UPDATE": op = SqlRedoParser.Op.UPDATE; break;
                    default: op = SqlRedoParser.Op.DELETE; break;
                }
                SqlRedoParser.ParsedChange parsed = parser.parse(op, row.sqlRedo, binding);
                if (parsed.op == null) {
                    parseFailures.incrementAndGet();
                    stats.addSkippedUnsupported(1);
                    log.warn("Skipping {} row on {}.{}: {} (sample: {})", row.operation,
                            row.segOwner, row.tableName, parsed.unsupportedReason,
                            truncate(row.sqlRedo, 120));
                    continue;
                }
                if (currentCsn < 0) {
                    currentCsn = row.csn;
                } else if (row.csn != currentCsn) {
                    flushTransaction(projectId, currentCsn, upserts, deletes);
                    upserts.clear();
                    deletes.clear();
                    currentCsn = row.csn;
                }
                if (op == SqlRedoParser.Op.DELETE) {
                    deletes.computeIfAbsent(binding, k -> new ArrayList<>()).add(parsed.row);
                } else {
                    upserts.computeIfAbsent(binding, k -> new ArrayList<>()).add(parsed.row);
                }
                if (row.rowTime != null) {
                    stats.touchEvent(row.rowTime.getTime());
                }
                all.add(row);
            }
        }
        if (currentCsn >= 0 && (!upserts.isEmpty() || !deletes.isEmpty())) {
            flushTransaction(projectId, currentCsn, upserts, deletes);
        }
        return all;
    }

    /**
     * Commit the buffered transaction on the target, renew the lease, then advance the SCN
     * watermark — data-first at-least-once: a crash between commit and advanceScn replays
     * the same CSCN against idempotent upserts.
     */
    private void flushTransaction(long projectId, long csn,
                                   Map<CdcTableBinding, List<Map<String, Object>>> upserts,
                                   Map<CdcTableBinding, List<Map<String, Object>>> deletes)
            throws Exception {
        try (Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig())) {
            target.setAutoCommit(false);
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : upserts.entrySet()) {
                applier.applyUpserts(target, e.getKey(), e.getValue());
            }
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : deletes.entrySet()) {
                applier.applyDeletes(target, e.getKey(), e.getValue());
            }
            target.commit();
        }
        ctx.requireLease();
        positionStore.advanceScn(projectId, csn);
        stats.recordPositionScn(csn);
        parseFailures.set(0);
    }

    private boolean matchesProjectTable(String owner, String table) {
        if (owner == null || table == null) {
            return false;
        }
        // Schema gate: two schemas with same-named tables must not both flow into CDC.
        // Oracle delivers SEG_OWNER in uppercase; the connection's source schema is
        // configured by the user and may have been entered in any case.
        String expected = ctx.getSourceSchema();
        if (expected != null && !expected.isBlank()
                && !owner.equalsIgnoreCase(expected)) {
            return false;
        }
        return ctx.getConfig().includesTable(table);
    }

    private boolean parseStrikeOut() {
        return parseFailures.get() > MAX_PARSE_FAILURES;
    }

    private void fatalStop(long projectId, Throwable failure,
                           CdcFailureClassifier.Kind kind) {
        String label = kind == CdcFailureClassifier.Kind.TRANSIENT
                ? "PARSE_STRIKE_OUT" : kind.name();
        String detail = label + ": " + (failure == null
                ? "repeated LogMiner parse failures"
                : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("LogMiner consumer for project {} parking in ERROR — {}", projectId, detail);
    }

    private boolean sleepWhileRunning(long delayMs) {
        long remaining = delayMs;
        while (remaining > 0 && running && !Thread.currentThread().isInterrupted()) {
            long slice = Math.min(100L, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return running && !Thread.currentThread().isInterrupted();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // best-effort: pooled connection return + END_LOGMNR are both benign failures
            }
        }
    }

    @Override
    public void requestStop() {
        running = false;
        LogMinerSession session = currentSession;
        if (session != null) {
            session.close();
        }
    }

    /**
     * Per-table binding cache (mirrors {@code CdcConsumer.BindingSupplier}). Oracle names
     * are upper-case by default; {@code MetadataReader.readTable} is called with the
     * UPPER-case spelling so the generic reader's exact-name filter matches.
     */
    private final class BindingSupplier {
        private final Map<String, CdcTableBinding> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());

        CdcTableBinding binding(String owner, String table) {
            String key = (owner + "." + table).toUpperCase(Locale.ROOT);
            CdcTableBinding cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn,
                        owner.toUpperCase(Locale.ROOT), table.toUpperCase(Locale.ROOT));
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("LogMiner cannot map source table {}.{} (missing or no PK);"
                            + " skipping its row events", owner, table);
                    return null;
                }
                CdcTableBinding b = CdcTableBinding.of(owner.toUpperCase(Locale.ROOT),
                        table.toUpperCase(Locale.ROOT), meta,
                        ctx.getConfig().targetTableName(meta.getName()),
                        Collections.emptyMap());
                cache.put(key, b);
                return b;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("LogMiner metadata read failed for "
                        + owner + "." + table, e);
            }
        }

        /** Drop the cached binding so the next DML on this table re-reads source columns. */
        void invalidate(String owner, String table) {
            String key = (owner + "." + table).toUpperCase(Locale.ROOT);
            CdcTableBinding removed = cache.remove(key);
            if (removed != null) {
                log.info("Invalidated LogMiner binding for {}.{} after DDL event",
                        owner, table);
            }
        }
    }
}
