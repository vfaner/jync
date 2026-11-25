package com.qqmu.jync.service.cdc;

import java.io.IOException;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.fanout.FanoutMetaSupplier;
import com.qqmu.jync.service.fanout.FanoutRuntime;
import com.qqmu.jync.service.fanout.FanoutRuntimeFactory;
import com.qqmu.jync.service.sync.AutoSyncService;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Fan-out capture consumer (MySQL binlog, first version): one source stream feeds a
 * {@link FanoutRuntime}'s transport, and one worker per target applies it with its own
 * connection/position. The capture position in {@code cdc_position} is the shared anchor; the
 * per-target acknowledged positions live in {@code fanout_position}.
 */
@Slf4j
public final class FanoutCdcConsumer implements ManagedCdcConsumer {

    private final Project project;
    private final SyncLockService.LockHandle lock;
    private final SyncContext ctx;
    private final BinlogClientFactory clientFactory;
    private final CdcPositionStore positionStore;
    private final CdcBaselineService baselineService;
    private final SyncTaskStore taskStore;
    private final CdcRuntimeStats stats;
    private final SyncProperties properties;
    private final FanoutRuntimeFactory runtimes;
    private final AutoSyncService autoSync;

    private volatile boolean running = true;
    private volatile boolean rebaselineRequested;
    private volatile BinaryLogClient client;
    private final LazyLobSpillStore lobSpillStore = new LazyLobSpillStore();

    public FanoutCdcConsumer(Project project, SyncLockService.LockHandle lock, SyncContext ctx,
                             BinlogClientFactory clientFactory, CdcPositionStore positionStore,
                             CdcBaselineService baselineService, SyncTaskStore taskStore,
                             CdcRuntimeStats stats, SyncProperties properties,
                             FanoutRuntimeFactory runtimes, AutoSyncService autoSync) {
        this.project = project;
        this.lock = lock;
        this.ctx = ctx;
        this.clientFactory = clientFactory;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.stats = stats;
        this.properties = properties;
        this.runtimes = runtimes;
        this.autoSync = autoSync;
    }

    @Override
    public void run() {
        long projectId = project.getId();
        taskStore.markCdcRunning(projectId);
        FanoutRuntime runtime = null;
        try {
            runtime = runtimes.create(project, ctx, "fanout-" + projectId, lock::renew, stats);
            runtime.startWorkers();
            while (running && !Thread.currentThread().isInterrupted()) {
                reconcileAutoSync(projectId);
                ensureBaseline(runtime, projectId);
                if (!running) {
                    break;
                }
                stream(projectId, runtime);
                if (!running || !rebaselineRequested) {
                    break;
                }
                // A new source table arrived while streaming: the position was dropped by
                // reconcileAutoSync, so the next iteration re-baselines every target before
                // resuming. The spools keep the old rows; baseline upserts make replay safe.
                rebaselineRequested = false;
            }
        } catch (Exception e) {
            String detail = CdcFailureClassifier.describe(e);
            stats.setLastError(detail);
            if (CdcFailureClassifier.isTransient(e)) {
                // A target or the source blinking must not park the project; supervision
                // restarts the capture and the per-target spools replay from their offsets.
                log.warn("Fan-out consumer for project {} stopped transiently: {}",
                        projectId, detail);
            } else {
                log.error("Fan-out consumer for project {} stopped: {}", projectId, detail);
                taskStore.recordCdcFailure(projectId, detail);
            }
        } finally {
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private void ensureBaseline(FanoutRuntime runtime, long projectId) throws Exception {
        CdcPosition position = positionStore.find(projectId).orElse(null);
        if (position != null && Boolean.TRUE.equals(position.isBaselineDone())) {
            return;
        }
        CdcPositionMark mark = baselineService.readCurrentPosition(ctx.getSourceConfig());
        positionStore.recordBaselineStart(projectId, mark.getFile(), mark.getPos(),
                mark.getGtidSet(), clientFactory.serverIdFor(projectId));
        runtime.baseline();
        positionStore.markBaselineDone(projectId);
        log.info("Fan-out baseline for project {} finished", projectId);
    }

    /**
     * Adds source tables that appeared after the auto-sync switch was enabled. A fan-out
     * re-baseline is the safe first version: the new table has no history on any target, and
     * every target must receive it before the shared stream resumes.
     */
    private void reconcileAutoSync(long projectId) {
        if (!ctx.getConfig().isAutoSyncNewTables()) {
            return;
        }
        try (java.sql.Connection conn = openSource()) {
            java.util.List<String> sourceTables = ctx.getSourceReader()
                    .listTableNames(conn, ctx.getSourceSchema());
            java.util.List<String> added = autoSync.reconcile(project, ctx.getConfig(),
                    sourceTables);
            if (!added.isEmpty()) {
                positionStore.delete(projectId);
                log.info("Fan-out auto-sync added {} table(s); dropping the capture anchor to"
                        + " re-baseline every target: {}", added.size(), added);
            }
        } catch (Exception e) {
            // A metadata blip must not stop the existing stream; the next reconnect retries.
            log.warn("Fan-out auto-sync reconciliation failed: {}", e.toString());
        }
    }

    private void stream(long projectId, FanoutRuntime runtime) throws Exception {
        CdcEventStreamHandler.TransactionSink capture =
                new CdcFanoutSink(runtime.transport());
        CdcEventStreamHandler.TransactionSink sink = (changes, mark) -> {
            capture.apply(changes, mark);
            if (mark.getFile() != null) {
                positionStore.advance(projectId, mark.getFile(), mark.getPos(),
                        mark.getGtidSet());
                stats.recordPosition(mark.getFile(), mark.getPos(), mark.getGtidSet());
            }
            stats.touchActivity();
        };
        CdcReconnectBackoff backoff = new CdcReconnectBackoff(
                properties.getCdc().getReconnectBackoffMs(),
                properties.getCdc().getReconnectBackoffMaxMs());
        while (running && !Thread.currentThread().isInterrupted()) {
            if (!lock.renew()) {
                log.warn("Fan-out consumer for project {} stopping: lease lost", projectId);
                break;
            }
            CdcPosition resume = positionStore.find(projectId).orElse(null);
            BinaryLogClient current = clientFactory.createClient(ctx.getSourceConfig(),
                    projectId, resume);
            this.client = current;
            java.sql.Connection bindingConn = openSource();
            FanoutMetaSupplier supplier = new FanoutMetaSupplier(
                    new com.qqmu.jync.service.fanout.FanoutBindingResolver(ctx,
                            bindingConn));
            CdcEventStreamHandler handler = new CdcEventStreamHandler(
                    ctx.getConfig(), ctx.getSourceSchema(), supplier, new CdcRowMapper(), stats,
                    current::getGtidSet, sink, lobSpillStore.get(properties),
                    properties.getLob().getCdcSpillThresholdBytes(),
                    CdcTransactionBudget.from(properties),
                    (db, sql, file, position) -> { }, null,
                    (db, table, sql) -> {
                        if (ctx.getConfig().isAutoSyncNewTables()) {
                            rebaselineRequested = true;
                            log.info("Fan-out capture saw CREATE TABLE {}.{}; reconnecting to"
                                    + " re-baseline", db, table);
                            disconnect(current);
                        }
                    });
            handler.seedCurrentFile(resume == null ? null : resume.getBinlogFile());
            handler.onEmptyTransaction(mark -> {
                if (mark.getFile() == null || !ctx.renewLease()) {
                    return;
                }
                positionStore.advance(projectId, mark.getFile(), mark.getPos(),
                        mark.getGtidSet());
                stats.recordPosition(mark.getFile(), mark.getPos(), mark.getGtidSet());
                stats.touchActivity();
            });
            handler.attachClient(current);
            current.registerEventListener(handler);
            current.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
                @Override
                public void onConnect(BinaryLogClient client) {
                    stats.setConnected(true);
                    stats.markStarted();
                }

                @Override
                public void onCommunicationFailure(BinaryLogClient client, Exception e) {
                    // connect() returns/throws; the loop below handles the retry.
                }
            });
            try {
                current.connect(properties.getCdc().getConnectTimeoutMs());
                // connect(timeout) only waits for the handshake; the stream lives in the
                // client's own thread. Keep this consumer parked here until a disconnect or
                // stop request, exactly like the single-target consumer's awaitStreamEnd.
                while (running && current.isConnected()
                        && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(200L);
                }
            } catch (Exception e) {
                log.warn("Fan-out capture for project {} disconnected: {}", projectId,
                        e.toString());
                stats.setConnected(false);
            } finally {
                handler.releaseSpill();
                positionStore.flush(projectId);
                disconnect(current);
                stats.setConnected(false);
                this.client = null;
                try {
                    bindingConn.close();
                } catch (Exception e) {
                    log.debug("Fan-out binding connection close failed: {}", e.toString());
                }
            }
            if (!running || rebaselineRequested) {
                break;
            }
            if (!CdcWaits.sleepWhileRunning(() -> running, backoff.nextDelayMs())) {
                break;
            }
        }
    }

    private java.sql.Connection openSource() throws Exception {
        return ctx.getConnectionProvider().open(ctx.getSourceConfig());
    }

    private static void disconnect(BinaryLogClient client) {
        try {
            client.disconnect();
        } catch (IOException | RuntimeException e) {
            log.debug("Fan-out client disconnect failed: {}", e.toString());
        }
    }

    @Override
    public void requestStop() {
        running = false;
        BinaryLogClient current = client;
        if (current != null) {
            disconnect(current);
        }
    }
}
