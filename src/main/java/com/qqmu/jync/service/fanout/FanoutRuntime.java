package com.qqmu.jync.service.fanout;

import java.nio.file.Path;
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
import tools.jackson.databind.ObjectMapper;

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
    private final Connection sourceConn;
    private final FanoutBindingResolver bindings;
    private final EventTransport transport;
    private final List<FanoutTargetWorker> workers = new CopyOnWriteArrayList<>();
    private final FanoutBaselineService baselineService;
    private final FanoutProgressStore progressStore;
    private final FanoutPositionStore positionStore;
    private final com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair;
    private final CdcRuntimeStats stats;
    private final Map<Long, Integer> restartAttempts = new ConcurrentHashMap<>();
    private final java.util.Set<Long> barrierAcked = ConcurrentHashMap.newKeySet();
    private volatile String baselineMarker;
    private ScheduledExecutorService supervisor;

    public FanoutRuntime(SyncContext sourceCtx, List<FanoutBaselineService.Target> targets,
                         CdcRuntimeStats stats, StructureSyncService structureSync,
                         FanoutProgressStore progressStore, FanoutPositionStore positionStore,
                         com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair,
                         ObjectMapper mapper, Path spoolDirectory, long maxSpoolBytes)
            throws Exception {
        this.sourceCtx = sourceCtx;
        this.targets = List.copyOf(targets);
        this.sourceConn = sourceCtx.getConnectionProvider()
                .open(sourceCtx.getSourceConfig());
        this.bindings = new FanoutBindingResolver(sourceCtx, sourceConn);
        this.transport = new SpoolingEventTransport(spoolDirectory, maxSpoolBytes, mapper);
        this.progressStore = progressStore;
        this.positionStore = positionStore;
        this.stats = stats;
        this.targetRepair = targetRepair;
        this.baselineService = new FanoutBaselineService(structureSync);
        for (FanoutBaselineService.Target target : targets) {
            String group = String.valueOf(target.targetDbId());
            EventTransport.Subscription subscription = transport.subscribe(group);
            workers.add(new FanoutTargetWorker(group, subscription, applierFor(target),
                    position -> onTargetCommit(target.targetDbId(), position)));
        }
    }

    /** One target's write path, with the missing-table repair shared by every target. */
    private EventApplier applierFor(FanoutBaselineService.Target target) {
        EventApplier applier = new JdbcFanoutApplier(target.context(), stats, bindings);
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
        for (int i = 0; i < workers.size(); i++) {
            FanoutTargetWorker worker = workers.get(i);
            if (worker.isAlive()) {
                continue;
            }
            FanoutBaselineService.Target target = targets.get(i);
            int attempts = restartAttempts.merge(target.targetDbId(), 1, Integer::sum);
            // Keep the subscription open: closing it would stop spooling for this target
            // between the failure and the replacement, losing the events in that window.
            if (attempts > 5) {
                log.error("Fan-out target {} stopped {} times; not restarting it again"
                        + " until the project restarts", target.targetDbId(), attempts - 1);
                continue;
            }
            log.warn("Fan-out target {} worker stopped ({}); restarting attempt {}",
                    target.targetDbId(), worker.getLastError(), attempts);
            FanoutTargetWorker replacement = newWorker(target, worker.subscription());
            workers.set(i, replacement);
            replacement.start();
        }
    }

    private FanoutTargetWorker newWorker(FanoutBaselineService.Target target) {
        return newWorker(target, transport.subscribe(String.valueOf(target.targetDbId())));
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
        List<FanoutBaselineService.TableLoad> loads =
                baselineService.baseline(sourceCtx, sourceConn, targets, transport);
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
        while (!barrierAcked.containsAll(wanted) && System.currentTimeMillis() < deadline) {
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

    @Override
    public void close() {
        if (supervisor != null) {
            supervisor.shutdownNow();
        }
        for (FanoutTargetWorker worker : workers) {
            worker.close();
        }
        try {
            transport.close();
        } catch (Exception e) {
            log.debug("Fan-out transport close failed: {}", e.toString());
        }
        try {
            sourceConn.close();
        } catch (Exception e) {
            log.debug("Fan-out source connection close failed: {}", e.toString());
        }
    }

    private void onTargetCommit(Long targetDbId, String position) {
        // A successful transaction proves the target is healthy again; a later outage gets
        // a fresh restart budget instead of inheriting an old count.
        restartAttempts.remove(targetDbId);
        // Same ruling for the shared repair budget: the counter is keyed by project, so
        // without this a fan-out project would spend its whole allowance across unrelated
        // repairs and then refuse a genuine one until the process restarts.
        if (targetRepair != null) {
            targetRepair.resetAttempts(sourceCtx.projectId());
        }
        if (position != null && position.equals(baselineMarker)) {
            barrierAcked.add(targetDbId);
            return;
        }
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
