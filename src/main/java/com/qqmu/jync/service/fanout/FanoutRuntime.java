package com.qqmu.jync.service.fanout;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.cdc.JdbcFanoutApplier;
import com.qqmu.jync.service.sync.StructureSyncService;
import com.qqmu.jync.service.sync.SyncContext;

import lombok.extern.slf4j.Slf4j;

/**
 * One fan-out run: owns the shared transport, the source connection used for bindings and the
 * baseline, and one {@link FanoutTargetWorker} per target. The capture consumer only feeds
 * this runtime; it never writes a target directly.
 */
@Slf4j
public final class FanoutRuntime implements AutoCloseable {

    /** Typed position tokens published by the non-binlog engines (see onTargetCommit). */
    public static final String TOKEN_LSN = "lsn:";
    public static final String TOKEN_SCN = "scn:";
    public static final String TOKEN_YSTREAM = "ystream:";

    private final SyncContext sourceCtx;
    private final List<FanoutBaselineService.Target> targets;
    private final FanoutBindingResolver bindings;
    private final EventTransport transport;
    private final List<FanoutTargetWorker> workers = new CopyOnWriteArrayList<>();
    private final FanoutBaselineService baselineService;
    private final FanoutProgressStore progressStore;
    private final FanoutPositionStore positionStore;
    private final com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair;
    private final int queryTimeoutSeconds;
    private final CdcRuntimeStats stats;
    private final Map<Long, Integer> restartAttempts = new ConcurrentHashMap<>();
    /**
     * Last acknowledged position per target, written to the metadata store at most once a
     * second. The position is display/ops state — a restart resumes from the transport's
     * acknowledged offset, not from this column — so twenty targets no longer mean twenty
     * H2 writes per source transaction.
     */
    private final Map<Long, String> pendingPositions = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastPositionWriteAt = new ConcurrentHashMap<>();
    private static final long POSITION_WRITE_INTERVAL_MS = 1_000L;
    private final java.util.Set<Long> barrierAcked = ConcurrentHashMap.newKeySet();
    /** Next allowed restart per target; dead workers back off instead of being abandoned. */
    private final Map<Long, Long> nextRestartAt = new ConcurrentHashMap<>();
    private static final long MAX_RESTART_DELAY_MS = TimeUnit.MINUTES.toMillis(5);
    private volatile String baselineMarker;
    /** Set by close(): a supervisor tick that is already running must not start workers. */
    private volatile boolean closed;
    private ScheduledExecutorService supervisor;

    public FanoutRuntime(SyncContext sourceCtx, List<FanoutBaselineService.Target> targets,
                         CdcRuntimeStats stats, StructureSyncService structureSync,
                         FanoutProgressStore progressStore, FanoutPositionStore positionStore,
                         com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair,
                         int queryTimeoutSeconds, EventTransport transport)
            throws Exception {
        this.sourceCtx = sourceCtx;
        this.targets = List.copyOf(targets);
        this.bindings = new FanoutBindingResolver(sourceCtx);
        this.transport = transport;
        this.progressStore = progressStore;
        this.positionStore = positionStore;
        this.stats = stats;
        this.targetRepair = targetRepair;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.baselineService = new FanoutBaselineService(structureSync);
        try {
            for (FanoutBaselineService.Target target : targets) {
                String group = String.valueOf(target.targetDbId());
                EventTransport.Subscription subscription = transport.subscribe(group);
                workers.add(new FanoutTargetWorker(group, subscription, applierFor(target),
                        position -> onTargetCommit(target.targetDbId(), position)));
            }
        } catch (RuntimeException e) {
            // The factory retries this constructor on every supervise tick; a subscribe or
            // worker build failure must not leak the transport (and its open files/client).
            try {
                transport.close();
            } catch (Exception close) {
                log.debug("Fan-out transport close after a failed start: {}",
                        close.toString());
            }
            throw e;
        }
    }

    /** One target's write path, with the missing-table repair shared by every target. */
    private EventApplier applierFor(FanoutBaselineService.Target target) {
        EventApplier applier = new JdbcFanoutApplier(target.context(), stats, bindings,
                queryTimeoutSeconds);
        return targetRepair == null ? applier
                : new RepairingEventApplier(applier, targetRepair, target.context(),
                        sourceCtx::getConfig);
    }

    /** Must run before the baseline so published rows are consumed immediately. */
    public synchronized void startWorkers() {
        workers.forEach(FanoutTargetWorker::start);
        supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fanout-supervisor-" + sourceCtx.projectId());
            t.setDaemon(true);
            return t;
        });
        supervisor.scheduleWithFixedDelay(this::restartDeadWorkers, 5, 5, TimeUnit.SECONDS);
    }

    /** Restarts a target whose worker stopped; the spool offset makes replay lossless. */
    private synchronized void restartDeadWorkers() {
        try {
            restartDeadWorkersOnce();
        } catch (RuntimeException e) {
            // ScheduledExecutorService drops a task after one escaping exception: a single
            // bad restart would silently stop supervision for every target of this run.
            log.warn("Fan-out supervisor tick for project {} failed: {}",
                    sourceCtx.projectId(), e.toString());
        }
    }

    private void restartDeadWorkersOnce() {
        if (closed) {
            return;
        }
        for (int i = 0; i < workers.size(); i++) {
            FanoutTargetWorker worker = workers.get(i);
            if (worker.isAlive()) {
                continue;
            }
            FanoutBaselineService.Target target = targets.get(i);
            // The supervisor ticks every 5 s. A fixed five-attempt cap abandoned a target
            // after 25 s — a short database restart was enough to leave it dead while the
            // project looked healthy. Back off (5 s → 10 s → … → 5 min) and keep trying.
            long now = System.currentTimeMillis();
            Long next = nextRestartAt.get(target.targetDbId());
            if (next != null && now < next) {
                continue;
            }
            Throwable lastError = worker.getLastError();
            if (lastError instanceof FanoutFatalTargetException) {
                // Rewinding would re-read the same corrupt record / over-cap spool forever.
                // Keep the target visible with its error; a restart or a re-baseline fixes it.
                // The MAX entry also keeps this branch from logging on every supervisor tick.
                log.error("Fan-out target {} stopped on a non-retryable error; not"
                        + " restarting this run: {}", target.targetDbId(), lastError.toString());
                nextRestartAt.put(target.targetDbId(), Long.MAX_VALUE);
                continue;
            }
            int attempts = restartAttempts.merge(target.targetDbId(), 1, Integer::sum);
            long delay = Math.min(MAX_RESTART_DELAY_MS,
                    5_000L << Math.min(attempts - 1, 6));
            nextRestartAt.put(target.targetDbId(), now + delay);
            // Keep the subscription open: closing it would stop spooling for this target
            // between the failure and the replacement, losing the events in that window.
            log.warn("Fan-out target {} worker stopped ({}); restart attempt {}, next delay"
                    + " {} ms", target.targetDbId(), worker.getLastError(), attempts, delay);
            if (closed) {
                return;
            }
            FanoutTargetWorker replacement = newWorker(target, worker.subscription());
            workers.set(i, replacement);
            replacement.start();
        }
    }

    private FanoutTargetWorker newWorker(FanoutBaselineService.Target target,
                                         EventTransport.Subscription subscription) {
        subscription.rewindToAck();
        String group = String.valueOf(target.targetDbId());
        return new FanoutTargetWorker(group, subscription, applierFor(target),
                position -> onTargetCommit(target.targetDbId(), position));
    }

    /** Live state of one target's worker, for the polled status fragment. */
    public record TargetState(Long targetDbId, boolean alive, long appliedTransactions,
                              String lastError, String lastAppliedPosition) {
    }

    /**
     * One entry per target, in target order. Read from the worker threads' volatile fields;
     * a worker replaced by the supervisor shows up as the new instance at the same index.
     */
    public List<TargetState> workerStates() {
        List<FanoutTargetWorker> snapshot = List.copyOf(workers);
        List<TargetState> states = new ArrayList<>();
        for (int i = 0; i < snapshot.size() && i < targets.size(); i++) {
            FanoutTargetWorker worker = snapshot.get(i);
            Throwable error = worker.getLastError();
            states.add(new TargetState(targets.get(i).targetDbId(), worker.isAlive(),
                    worker.getAppliedTransactions(), error == null ? null : error.toString(),
                    worker.getLastAppliedPosition()));
        }
        return states;
    }

    /** Runs the shared baseline; returns the per-table loads (empty when nothing to load). */
    public List<FanoutBaselineService.TableLoad> baseline() throws Exception {
        // Its own connection: workers are already resolving bindings on the runtime's
        // connection from other threads, and a JDBC connection is not thread-safe (SQL
        // Server corrupts its TDS stream outright; others close the open ResultSet).
        List<FanoutBaselineService.TableLoad> loads;
        try (Connection baselineConn = sourceCtx.getConnectionProvider()
                .open(sourceCtx.getSourceConfig())) {
            loads = baselineService.baseline(sourceCtx, baselineConn, targets, transport);
        }
        if (loads.isEmpty()) {
            return loads;
        }
        // Barrier: every worker must acknowledge the final marker before the baseline is
        // reported complete, otherwise a crash right after "done" could restart capture from
        // the anchor with a target that never consumed the rows.
        String marker = "baseline-done:" + UUID.randomUUID();
        barrierAcked.clear();
        baselineMarker = marker;
        transport.publish(ChangeEvent.commit(marker, marker, System.currentTimeMillis()));
        java.util.Set<Long> wanted = new java.util.LinkedHashSet<>();
        for (FanoutBaselineService.Target target : targets) {
            wanted.add(target.targetDbId());
        }
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (!barrierAcked.containsAll(wanted)
                && System.currentTimeMillis() < deadline
                && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(50L);
        }
        if (!barrierAcked.containsAll(wanted)) {
            throw new IllegalStateException("fan-out baseline barrier timed out: acked "
                    + barrierAcked + " of " + wanted);
        }
        for (FanoutBaselineService.TableLoad load : loads) {
            for (FanoutBaselineService.Target target : targets) {
                com.qqmu.jync.model.FanoutProgress progress = progressStore.loadOrCreate(
                        sourceCtx.projectId(), target.targetDbId(), load.table());
                progress.setInitialLoadDone(true);
                progress.setRowsSyncedTotal(load.rows());
                progress.setLastSyncTime(java.time.Instant.now());
                progressStore.save(progress);
            }
        }
        return loads;
    }

    public EventTransport transport() {
        return transport;
    }

    /** See {@link EventTransport#needsRebaseline()}. */
    public boolean needsRebaseline() {
        return transport.needsRebaseline();
    }

    public void clearRebaselineMarks() {
        transport.clearRebaselineMarks();
    }

    /**
     * Drops every cached source binding. An administrator-executed DDL changes the target
     * columns the capture projects; the capture-side supplier has its own copy of this
     * cache, and the workers would otherwise keep writing the pre-DDL column list until the
     * project restarts.
     */
    public void invalidateBindings() {
        bindings.invalidateAll();
    }

    @Override
    public synchronized void close() {
        // Same monitor as the supervisor: without it a tick can create and start a
        // replacement after close() has already closed the subscription, leaving a thread
        // spinning on poll() with no owner.
        closed = true;
        if (supervisor != null) {
            supervisor.shutdownNow();
        }
        for (FanoutTargetWorker worker : workers) {
            worker.close();
        }
        // The throttle may still hold the last transaction's position: write it before the
        // runtime (and its metadata connection) goes away. The transport close is in a
        // finally so a metadata failure cannot leak the spool handles / Redis client.
        try {
            for (Long targetDbId : pendingPositions.keySet()) {
                persistTargetPosition(targetDbId);
            }
        } finally {
            try {
                transport.close();
            } catch (Exception e) {
                log.debug("Fan-out transport close failed: {}", e.toString());
            }
        }
    }

    private void onTargetCommit(Long targetDbId, String position) {
        // A successful transaction proves the target is healthy again; a later outage gets
        // a fresh restart budget instead of inheriting an old count.
        restartAttempts.remove(targetDbId);
        nextRestartAt.remove(targetDbId);
        // Same ruling for the shared repair budget: the counter is keyed by project, so
        // without this a fan-out project would spend its whole allowance across unrelated
        // repairs and then refuse a genuine one until the process restarts.
        if (targetRepair != null) {
            // Per target: a healthy target's commits must not keep resetting a failed
            // target's repair budget until CAP_REACHED never fires.
            targetRepair.resetAttempts(sourceCtx.projectId(), targetDbId);
        }
        if (position != null && position.equals(baselineMarker)) {
            barrierAcked.add(targetDbId);
            return;
        }
        rememberTargetPosition(targetDbId, position);
    }

    private void rememberTargetPosition(Long targetDbId, String position) {
        if (position == null) {
            return;
        }
        pendingPositions.put(targetDbId, position);
        long now = System.currentTimeMillis();
        Long last = lastPositionWriteAt.get(targetDbId);
        if (last == null || now - last >= POSITION_WRITE_INTERVAL_MS) {
            persistTargetPosition(targetDbId);
        }
    }

    private void persistTargetPosition(Long targetDbId) {
        String position = pendingPositions.get(targetDbId);
        if (position == null) {
            return;
        }
        lastPositionWriteAt.put(targetDbId, System.currentTimeMillis());
        recordTargetPosition(positionStore, sourceCtx.projectId(), targetDbId, position);
    }

    static void recordTargetPosition(FanoutPositionStore store, Long projectId,
                                             Long targetDbId, String position) {
        if (position == null || position.startsWith("baseline")) {
            return;
        }
        // Non-binlog engines publish a typed token: LSN/SCN/YStream positions are not a
        // "file:offset" pair, and storing them as one would misreport progress.
        if (position.startsWith(TOKEN_LSN)) {
            store.advance(projectId, targetDbId, null, 0L, null,
                    position.substring(TOKEN_LSN.length()), null);
            return;
        }
        if (position.startsWith(TOKEN_SCN)) {
            try {
                store.advance(projectId, targetDbId, null, 0L, null, null,
                        Long.parseLong(position.substring(TOKEN_SCN.length())));
            } catch (NumberFormatException e) {
                // Malformed token: keep the in-memory progress only.
            }
            return;
        }
        if (position.startsWith(TOKEN_YSTREAM)) {
            // YStream's packed position is an opaque text token; the LSN column is its
            // generic holder (same column the single-target engine writes it to).
            store.advance(projectId, targetDbId, null, 0L, null,
                    position.substring(TOKEN_YSTREAM.length()), null);
            return;
        }
        int colon = position.lastIndexOf(':');
        if (colon <= 0) {
            return;
        }
        try {
            store.advance(projectId, targetDbId, position.substring(0, colon),
                    Long.parseLong(position.substring(colon + 1)), null, null, null);
        } catch (NumberFormatException e) {
            // Non-binlog position tokens are not a binlog file/offset; the target worker's
            // lastAppliedPosition still records progress in memory.
        }
    }
}
