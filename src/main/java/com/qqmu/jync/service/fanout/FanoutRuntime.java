package com.qqmu.jync.service.fanout;

import java.nio.file.Path;
import java.sql.Connection;
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

    private final SyncContext sourceCtx;
    private final List<FanoutBaselineService.Target> targets;
    private final Connection sourceConn;
    private final FanoutBindingResolver bindings;
    private final EventTransport transport;
    private final List<FanoutTargetWorker> workers = new CopyOnWriteArrayList<>();
    private final FanoutBaselineService baselineService;
    private final FanoutProgressStore progressStore;
    private final FanoutPositionStore positionStore;
    private final CdcRuntimeStats stats;
    private final Map<Long, Integer> restartAttempts = new ConcurrentHashMap<>();
    private final java.util.Set<Long> barrierAcked = ConcurrentHashMap.newKeySet();
    private volatile String baselineMarker;
    private ScheduledExecutorService supervisor;

    public FanoutRuntime(SyncContext sourceCtx, List<FanoutBaselineService.Target> targets,
                         CdcRuntimeStats stats, StructureSyncService structureSync,
                         FanoutProgressStore progressStore, FanoutPositionStore positionStore,
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
        this.baselineService = new FanoutBaselineService(structureSync);
        for (FanoutBaselineService.Target target : targets) {
            String group = String.valueOf(target.targetDbId());
            EventTransport.Subscription subscription = transport.subscribe(group);
            EventApplier applier = new JdbcFanoutApplier(target.context(), stats, bindings);
            workers.add(new FanoutTargetWorker(group, subscription, applier,
                    position -> onTargetCommit(target.targetDbId(), position)));
        }
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
        EventApplier applier = new JdbcFanoutApplier(target.context(), stats, bindings);
        return new FanoutTargetWorker(group, subscription, applier,
                position -> onTargetCommit(target.targetDbId(), position));
    }

    public void baseline() throws Exception {
        List<FanoutBaselineService.TableLoad> loads =
                baselineService.baseline(sourceCtx, sourceConn, targets, transport);
        if (loads.isEmpty()) {
            return;
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
        if (position != null && position.equals(baselineMarker)) {
            barrierAcked.add(targetDbId);
            return;
        }
        recordTargetPosition(positionStore, sourceCtx.projectId(), targetDbId, position);
    }

    private static void recordTargetPosition(FanoutPositionStore store, Long projectId,
                                             Long targetDbId, String position) {
        if (position == null || position.startsWith("baseline")) {
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
