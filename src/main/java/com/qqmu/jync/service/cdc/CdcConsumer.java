package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;
import com.qqmu.jync.util.JdbcUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * The long-lived CDC pipeline of one project (task book B6/B7): baseline if needed, then
 * an endless connect → stream → drop → classify → backoff → reconnect loop that owns the
 * project's daemon lease for its whole life.
 *
 * <p>Exit paths, each with its own bookkeeping contract:
 * <ul>
 *   <li><b>requestStop</b> (manager stop / shutdown): the client is disconnected, the loop
 *       breaks cleanly, no failure is recorded — the buffered transaction simply replays
 *       after the next start (at-least-once, zero loss).</li>
 *   <li><b>stop-requested baseline abort</b> (final review FIX 2): a stop whose join budget
 *       expired interrupts the chunked load; the abort surfaces at the catch-all, which
 *       exits quietly when {@code running} is false or the interrupt flag is set — no
 *       ERROR hat, no recordCdcFailure, and the baseline stays not-done.</li>
 *   <li><b>lease loss</b> (controller ruling R1): detected by an explicit
 *       {@code lock.renew()} at the top of every loop iteration, before a new client is
 *       built. Exits quietly with an INFO log — never ERROR, never recordCdcFailure: the
 *       takeover node may already have marked the project RUNNING, and an ERROR here would
 *       stomp that state and freeze every node's supervise through the ERROR-skip.</li>
 *   <li><b>fatal stream failure</b> (PURGED / SERVER_ID_CONFLICT / AUTH_FAILURE, or the
 *       deserialization strike-out): recordCdcFailure + ERROR log, then the thread ends.
 *       The registration stays in the manager until supervise prunes it (R2); the ERROR
 *       hat blocks restarts until a human rebaselines (B7).</li>
 * </ul>
 *
 * <p>Position discipline is data-first: one binlog XID = one target commit, then the lease
 * is re-checked, then (and only then) the position advances. A crash anywhere in between
 * replays the transaction against idempotent upserts.
 */
@Slf4j
public final class CdcConsumer implements ManagedCdcConsumer {

    /** Consecutive deserialization failures above this count, with no successful
     * transaction in between, are treated as fatal (data corruption / version skew). */
    private static final int MAX_DESERIALIZATION_FAILURES = 5;

    private final Project project;
    private final SyncLockService.LockHandle lock;
    private final SyncContext ctx;
    private final BinlogClientFactory clientFactory;
    private final CdcPositionStore positionStore;
    private final CdcBaselineService baselineService;
    private final SyncTaskStore taskStore;
    private final SyncStateWriter stateWriter;
    private final DataSourceManager dataSourceManager;
    private final CdcRuntimeStats stats;
    private final SyncProperties properties;

    private final CdcEventApplier applier;
    private final CdcEventStreamHandler.TransactionSink sink;
    private final CdcTableMetaSupplier supplier;

    /** Cross-thread stop flag: requestStop is called from manager/scheduler threads. */
    private volatile boolean running = true;
    /** The client of the current loop iteration; requestStop disconnects it. */
    private volatile BinaryLogClient client;

    /**
     * Last binlog file seen, for the NULL-file guard: the handler's currentFile stays null
     * until the first ROTATE event and a mid-file resume produces no initial ROTATE, so a
     * position mark can legitimately carry a null file. Seeded from the resume position;
     * never pass a null file to {@code positionStore.advance}.
     */
    private volatile String lastFile;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicInteger deserializationFailures = new AtomicInteger();

    /** Assigned in run() before the loop; the retry path consults it after the stream ends. */
    private CdcReconnectBackoff backoff;

    /** Per-table aggregated change-log flusher (10s windows, final flush on stop). */
    private final CdcChangeWindowLogger windowLogger;

    /**
     * Temp files for oversized values in a buffered binlog transaction. Built on first
     * use, and only when {@code sync.lob.streaming-enabled} is on.
     */
    private volatile LobSpillStore lobSpillStore;

    /**
     * Ruling R3b: a connection must demonstrably LIVE before the reconnect backoff may
     * reset — an unconditional reset in onConnect turned a server that accepts and
     * immediately kicks (the purge case before R3a classified it) into a 1s hot loop
     * forever instead of escalating 1s→2s→4s…→60s. {@code connectedAtNs} is the nanoTime
     * of the current connection's onConnect (0 = this iteration never connected); the run
     * loop measures the dwell after the stream ends and resets only past the threshold.
     */
    private final AtomicLong connectedAtNs = new AtomicLong();

    /** Minimum demonstrated connection lifetime before the backoff resets (ruling R3b). */
    static final long MIN_BACKOFF_RESET_DWELL_MS = 30_000L;

    public CdcConsumer(Project project, SyncLockService.LockHandle lock, SyncContext ctx,
                       BinlogClientFactory clientFactory, CdcPositionStore positionStore,
                       CdcBaselineService baselineService, SyncTaskStore taskStore,
                       SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                       CdcRuntimeStats stats, SyncProperties properties) {
        this.project = project;
        this.lock = lock;
        this.ctx = ctx;
        this.clientFactory = clientFactory;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.stateWriter = stateWriter;
        this.dataSourceManager = dataSourceManager;
        this.stats = stats;
        this.properties = properties;
        // R3: the same timeout source DataSyncService uses (SyncProperties.queryTimeoutSeconds),
        // so CDC apply statements are bounded exactly like polling write statements.
        this.applier = new CdcEventApplier(ctx, stats, properties.getQueryTimeoutSeconds());
        // 聚合变更日志:按表累计 upsert/delete,到龄一条;停机兜底刷剩余。
        this.windowLogger = new CdcChangeWindowLogger(project.getId(), stats, stateWriter,
                taskStore, properties.getCdc().getChangeWindowMs());
        this.sink = transactionSink();
        this.supplier = new BindingSupplier();
    }

    @Override
    public void run() {
        long projectId = project.getId();
        taskStore.markCdcRunning(projectId);
        // The stop line alone reads like a crash loop on the change log: every restart
        // writes "stopped" but nothing says a new consumer came up. Pair it with a start
        // line so a restart is visibly one stop + one start. Best-effort: a failed audit
        // write must not keep the stream from running.
        try {
            stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                    ChangeType.INFO, "CDC consumer started", true, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the CDC start log for project {}: {}",
                    projectId, e.getMessage());
        }
        try {
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !Boolean.TRUE.equals(position.isBaselineDone())) {
                baselineService.runBaseline(ctx, lock::renew);   // 抛异常 → catch-all 走停机
                position = positionStore.find(projectId).orElse(null);  // 基线写入了新位点
            }
            if (position != null) {
                lastFile = position.getBinlogFile();             // NULL-FILE 守卫的种子
            }
            backoff = new CdcReconnectBackoff(
                    properties.getCdc().getReconnectBackoffMs(),
                    properties.getCdc().getReconnectBackoffMaxMs());
            windowLogger.start();
            while (running && !Thread.currentThread().isInterrupted()) {
                // R1: between-transaction lease liveness. requireLease only covers the write
                // path; without this check a lost lease classifies as TRANSIENT and the loop
                // reconnects forever, writing WITHOUT a lease while the takeover node also
                // writes — a double writer, which B6 forbids.
                if (!lock.renew()) {
                    log.warn("CDC consumer for project {} stopping: lease lost (another node"
                            + " may have taken over)", projectId);
                    stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                            ChangeType.INFO, "CDC consumer stopped: lease lost", true, 0L);
                    break;                                       // 安静退出:绝不 ERROR
                }
                lastFailure.set(null);
                connectedAtNs.set(0L);                             // R3b:本轮连接的存活计时从零起
                BinaryLogClient client = clientFactory.createClient(
                        ctx.getSourceConfig(), projectId,
                        positionStore.find(projectId).orElse(null));
                this.client = client;
                CdcEventStreamHandler handler = wire(client);      // handler + sink + lifecycle
                try {
                    client.connect(properties.getCdc().getConnectTimeoutMs());
                    awaitStreamEnd(client);
                } catch (Exception e) {
                    lastFailure.compareAndSet(null, e);
                } finally {
                    // A failed flush keeps its buffer (and spilled files) for the replay
                    // story, but this handler dies with the connection: release now.
                    handler.releaseSpill();
                }
                stats.setConnected(false);
                if (!running) {
                    break;                                       // requestStop 路径:干净退出
                }
                // R1: the handler-failure ref comes FIRST. 0.41.5 swallows listener
                // exceptions, so a choke inside event handling never reaches
                // onCommunicationFailure — the handler stores the failure, disconnects the
                // client itself, and this ref is the only carrier of the real cause. Each
                // observed handler failure counts toward the strike-out (repeated poison
                // events park the project; a single transient sink failure classifies
                // TRANSIENT below and reconnects, and one applied transaction resets the
                // counter to zero).
                Throwable handlerFailure = handler.takeFailure();
                if (handlerFailure != null) {
                    deserializationFailures.incrementAndGet();
                }
                Throwable failure = handlerFailure != null ? handlerFailure : lastFailure.get();
                CdcFailureClassifier.Kind kind = failure == null
                        ? CdcFailureClassifier.Kind.TRANSIENT
                        : CdcFailureClassifier.classify(failure);
                if (kind != CdcFailureClassifier.Kind.TRANSIENT || deserializationStrikeOut()) {
                    fatalStop(projectId, failure, kind);         // ERROR + recordLog + break
                    break;
                }
                // R3b: reset the escalation only when the connection that just ended had
                // demonstrably lived; an immediate accept-then-kick must keep escalating.
                long connectedAt = connectedAtNs.get();
                long dwellMs = connectedAt == 0L
                        ? 0L : (System.nanoTime() - connectedAt) / 1_000_000L;
                if (shouldResetBackoff(dwellMs)) {
                    backoff.reset();
                }
                long delay = backoff.nextDelayMs();
                // failure can legitimately be null (a clean server-side drop records no
                // cause); describe(null) would NPE into the catch-all and park the project
                // in ERROR over a benign reconnect.
                // C3:断流期间面板给出原因(红点只说「断了」,这里说「为什么」);
                // 重连成功由 onConnect 清空,不会留下过期错误。
                stats.setLastError(failure == null
                        ? "stream dropped: no failure recorded"
                        : CdcFailureClassifier.describe(failure));
                // #10 fix: a benign reconnect (no failure recorded) is INFO — it is the
                // normal lifecycle on DST / idle-NAT / source restart. Only a drop with
                // a real failure is WARN.
                if (failure == null) {
                    log.info("CDC stream for project {} dropped cleanly, retrying in {} ms",
                            projectId, delay);
                } else {
                    log.warn("CDC stream for project {} dropped ({}), retrying in {} ms",
                            projectId, CdcFailureClassifier.describe(failure), delay);
                }
                if (!sleepWhileRunning(delay)) {
                    break;   // requestStop 落在退避期:≤100ms 内退出(分片 sleep)
                }
            }
        } catch (RuntimeException | Error e) {
            if (e instanceof LockLostException) {
                // R1 reached through the baseline path: the lease went away while the
                // full load ran, and runBaseline left the position row for the takeover
                // node. Quiet exit — an ERROR here would stomp the owner's state.
                log.info("CDC consumer for project {} stopped: lease lost during the"
                        + " baseline ({}); not parking it in ERROR", projectId, e.toString());
            } else if (!running || Thread.currentThread().isInterrupted()) {
                // Stop-requested abort (final review FIX 2): the failure surfaced while a
                // stop was in flight — typically the baseline's chunked load interrupted at
                // a chunk boundary. Exit quietly: no ERROR hat, no recordCdcFailure (an
                // ERROR here would freeze every node's supervise over a legitimate stop,
                // and the stopper already owns the task state). The baseline stays
                // not-done and reruns on the next start; the finally block below releases
                // the lease and records the ordinary INFO stop line.
                log.info("CDC consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else {
                String detail = CdcFailureClassifier.describe(e);
                stats.setLastError(detail);  // C3:catch-all 停机也让面板说出原因
                taskStore.recordCdcFailure(projectId, detail);
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.ERROR, "CDC consumer failed: " + detail, false, 0L);
                log.error("CDC consumer for project {} failed", projectId, e);
            }
        } finally {
            disconnectQuietly(this.client);                      // 覆盖 stop 落在建流窗口的边界
            windowLogger.close();                                // 先断流再兜底刷聚合日志,顺序在 stop 标记之前
            lock.close();                                        // 释放租约:他节点可立即接管
            stats.setConnected(false);
            try {
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.INFO, "CDC consumer stopped", true, 0L);
            } catch (RuntimeException e) {
                log.warn("Could not record the CDC stop log for project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }

    /**
     * Stops the consumer: the stream is disconnected, {@code connect} returns, the loop
     * exits through the clean path (no failure recorded). The transaction currently
     * buffered in the handler is dropped with its position un-advanced — the next start
     * replays it (zero loss).
     */
    @Override
    public void requestStop() {
        running = false;
        BinaryLogClient current = this.client;
        if (current != null && current.isConnected()) {
            try {
                current.disconnect();
            } catch (IOException e) {
                log.warn("Disconnecting the CDC stream for project {} failed: {}",
                        project.getId(), e.getMessage());
            }
        }
    }

    /** The backoff-reset dwell gate (ruling R3b); package-private for tests. */
    static boolean shouldResetBackoff(long dwellMs) {
        return dwellMs >= MIN_BACKOFF_RESET_DWELL_MS;
    }

    /** The applier this consumer applies transactions with; package-private for tests (R3). */
    CdcEventApplier applierForTest() {
        return applier;
    }

    /** The reconnect backoff this consumer escalates with; package-private for tests (R3b). */
    CdcReconnectBackoff backoffForTest() {
        return backoff;
    }

    /** The binding supplier this consumer feeds the stream handler; package-private for tests. */
    CdcTableMetaSupplier supplierForTest() {
        return supplier;
    }

    /**
     * Blocks until the stream ends. {@code connect(timeoutMs)} in the Debezium fork only
     * bounds the handshake: it returns once the connection is up and the event loop keeps
     * running on the client's own thread. This wait restores the blocking-until-disconnect
     * contract the reconnect loop is written against; 100ms slices keep requestStop
     * responsive without depending on the disconnect landing first.
     */
    private void awaitStreamEnd(BinaryLogClient client) {
        while (running && !Thread.currentThread().isInterrupted() && client.isConnected()) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 100ms 分片 sleep;片间查 running/中断位,被 interrupt 时恢复中断位并返回 false。 */
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

    /** > 5 consecutive deserialization failures with no successful transaction between. */
    private boolean deserializationStrikeOut() {
        return deserializationFailures.get() > MAX_DESERIALIZATION_FAILURES;
    }

    /** ERROR 帽子 + 审计日志,线程随后结束(B7:等人处置,不自动重启)。 */
    private void fatalStop(long projectId, Throwable failure, CdcFailureClassifier.Kind kind) {
        // A TRANSIENT kind can only reach fatalStop via the deserialization strike-out.
        String label = kind == CdcFailureClassifier.Kind.TRANSIENT
                ? "DESERIALIZATION_STRIKE_OUT" : kind.name();
        String detail = label + ": " + (failure == null
                ? "repeated binlog deserialization failures"
                : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);      // C3:面板与 sync_task 收到同一份措辞
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("CDC consumer for project {} parking in ERROR — {}", projectId, detail);
    }

    private void disconnectQuietly(BinaryLogClient client) {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
            } catch (IOException e) {
                log.debug("Disconnecting the CDC client for project {} failed: {}",
                        project.getId(), e.getMessage());
            }
        }
    }

    /** handler(每条连接新建)+ lifecycle 监听;GTID 集合直接取自客户端。 */
    private CdcEventStreamHandler wire(BinaryLogClient client) {
        CdcEventStreamHandler handler = new CdcEventStreamHandler(
                ctx.getConfig(), ctx.getSourceSchema(), supplier, new CdcRowMapper(), stats,
                client::getGtidSet, sink, spillStore(),
                properties.getLob().getCdcSpillThresholdBytes());
        handler.attachClient(client);                            // R1:choke 时自行断连
        client.registerEventListener(handler);
        client.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
            @Override
            public void onConnect(BinaryLogClient client) {
                stats.setConnected(true);
                // C3:吞吐窗口从真实建流时刻起算;连上即宣告上一次失败已成历史——
                // 面板上的 lastError 随之清空(null,模板据此隐藏整行),瞬态故障
                // 恢复后绝不留下过期错误。与 setConnected 同线程写入(volatile)。
                stats.markStarted();
                stats.setLastError(null);
                // R3b: 只记录连上的时刻;是否值得把退避清零,由 run 循环按存活时长裁定
                connectedAtNs.set(System.nanoTime());
            }

            @Override
            public void onCommunicationFailure(BinaryLogClient client, Exception e) {
                lastFailure.set(e);                              // 本次连接的终止原因
            }

            @Override
            public void onEventDeserializationFailure(BinaryLogClient client, Exception e) {
                lastFailure.compareAndSet(null, e);
                if (deserializationFailures.incrementAndGet() > MAX_DESERIALIZATION_FAILURES) {
                    // The client would otherwise keep streaming past poison events forever
                    // (deserialization failures alone never drop the connection); force the
                    // drop so the reconnect loop can judge the strike-out fatal.
                    log.error("CDC stream for project {} hit {} consecutive deserialization"
                            + " failures; dropping the connection",
                            project.getId(), deserializationFailures.get());
                    disconnectQuietly(client);
                }
            }

            @Override
            public void onDisconnect(BinaryLogClient client) {
                stats.setConnected(false);
            }
        });
        return handler;
    }

    /** The LOB spill store, or {@code null} when streaming is switched off. */
    private LobSpillStore spillStore() {
        if (!properties.getLob().isStreamingEnabled()) {
            return null;
        }
        LobSpillStore store = lobSpillStore;
        if (store == null) {
            synchronized (this) {
                store = lobSpillStore;
                if (store == null) {
                    store = LobSpillStore.from(properties);
                    lobSpillStore = store;
                }
            }
        }
        return store;
    }

    /**
     * 一个 binlog 事务 → 一次目标提交 → 一次租约复检 → 一次位点持久化(先数据后位点,
     * at-least-once)。NULL-FILE 守卫:handler 的 currentFile 在首个 ROTATE 之前为 null,
     * 中途续读也不产生初始 ROTATE——用最近已知文件名兜底,绝不把 null 传给 advance。
     */
    private CdcEventStreamHandler.TransactionSink transactionSink() {
        return (changes, mark) -> {
            long projectId = project.getId();
            Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig());
            boolean originalAutoCommit = true;
            try {
                originalAutoCommit = target.getAutoCommit();
                target.setAutoCommit(false);
                for (CdcEventStreamHandler.TableChange tc : changes) {
                    applier.applyUpserts(target, tc.binding, tc.upserts);
                    applier.applyDeletes(target, tc.binding, tc.deletes);
                }
                target.commit();
            } catch (Exception e) {
                try { target.rollback(); } catch (SQLException ignored) { }
                throw e instanceof RuntimeException r ? r : new RuntimeException(e);
            } finally {
                try {
                    if (originalAutoCommit) target.setAutoCommit(true);
                } catch (SQLException ignored) { }
                try { target.close(); } catch (SQLException ignored) { }
            }
            ctx.requireLease();                                  // 丢租约 → 位点不动,接管方重放
            String file = mark.getFile() != null ? mark.getFile() : lastFile;
            if (file != null) {
                positionStore.advance(projectId, file, mark.getPos(), mark.getGtidSet());
                stats.recordPosition(file, mark.getPos(), mark.getGtidSet());
                lastFile = file;
            } else {
                log.warn("CDC position for project {} not advanced: binlog file is still"
                        + " unknown (no ROTATE event and no resume file); the transaction"
                        + " will replay after the next reconnect", projectId);
            }
            stats.touchEvent(System.currentTimeMillis());
            deserializationFailures.set(0);                      // 成功事务:清零连败计数
        };
    }

    /**
     * Per-table binding cache (survives reconnects; DDL invalidates). The lookup is
     * case-insensitive on purpose — checkpoint ⑦: binlog TABLE_MAP names can differ in
     * case from the configured/actual names, and while {@code SyncConfig.includesTable}
     * and {@code targetTableName} already match case-insensitively,
     * {@code MetadataReader.readTable} filters driver rows with a case-SENSITIVE exact
     * name comparison (GenericMetadataReader.isRequestedObject). Feeding it the binlog
     * spelling would return an empty TableMeta (no PK) and silently drop the table out of
     * CDC. So the actual name is resolved from information_schema first and every
     * downstream query uses that exact spelling.
     */
    private final class BindingSupplier implements CdcTableMetaSupplier {

        /** "db.table" (any case) -> binding; a case-insensitive TreeMap unifies spellings. */
        private final Map<String, CdcTableBinding> cache = Collections
                .synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

        @Override
        public CdcTableBinding binding(String database, String table) {
            String key = database + "." + table;
            CdcTableBinding cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                String actual = resolveActualTableName(conn, database, table);
                if (actual == null) {
                    // The ONLY legitimate null: the table is genuinely absent (dropped
                    // mid-stream or never existed) — the handler marks it unsupported and
                    // the stream moves on.
                    log.warn("CDC cannot find source table {}.{}; skipping its row events",
                            database, table);
                    return null;
                }
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn, database, actual);
                Map<String, String> charsets = readColumnCharsets(conn, database, actual);
                CdcTableBinding binding = CdcTableBinding.of(database, actual, meta,
                        ctx.getConfig().targetTableName(actual), charsets);
                cache.put(key, binding);
                return binding;
            } catch (SQLException e) {
                // Transient metadata failures (pool exhaustion, source failover, query
                // timeout) must PROPAGATE, not degrade to "unsupported table": returning
                // null here would let the transaction commit WITHOUT this table's rows
                // while the position still advances — silent permanent row loss, breaking
                // the at-least-once contract. The thrown exception leaves onTableMap, the
                // client treats a listener failure as a communication failure and drops
                // the connection, the reconnect loop classifies it TRANSIENT and replays
                // the whole transaction from the last position. A permanently broken
                // metadata read then loops at max backoff — visible, and safe.
                // (Wrapped because CdcTableMetaSupplier.binding declares no checked
                // exception; the cause is preserved for CdcFailureClassifier.)
                throw new IllegalStateException("CDC metadata read failed for "
                        + database + "." + table, e);
            }
            // RuntimeExceptions propagate as-is through the same listener-failure path.
        }

        @Override
        public void invalidate(String database, String table) {
            if (database == null) {
                cache.clear();                                   // DDL:全清(Review Focus #1)
            } else {
                cache.remove(database + "." + table);
            }
        }

        /** On-disk spelling of the table, matched case-insensitively; null when absent. */
        private String resolveActualTableName(Connection conn, String database, String table)
                throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES"
                            + " WHERE TABLE_SCHEMA = ? AND LOWER(TABLE_NAME) = LOWER(?) LIMIT 1")) {
                JdbcUtil.applyQueryTimeout(ps, properties.getMetadataQueryTimeoutSeconds());
                ps.setString(1, database);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }

        /** 每列字符集(binlog 传原始字节,行映射按列字符集解码),大写列名为键。 */
        private Map<String, String> readColumnCharsets(Connection conn, String database,
                                                       String table) throws SQLException {
            Map<String, String> charsets = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COLUMN_NAME, CHARACTER_SET_NAME FROM information_schema.COLUMNS"
                            + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                JdbcUtil.applyQueryTimeout(ps, properties.getMetadataQueryTimeoutSeconds());
                ps.setString(1, database);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String charset = rs.getString(2);
                        if (charset != null && !charset.isBlank()) {
                            charsets.put(rs.getString(1).toUpperCase(Locale.ROOT), charset);
                        }
                    }
                }
            }
            return charsets;
        }
    }
}
