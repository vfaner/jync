package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import com.qqmu.jync.service.sync.SyncStateWriter;
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

    /**
     * Handler failures already disconnect the stream. Repeating one on every reconnect
     * cannot help (a poison event or a deterministic publish failure), so after this many
     * consecutive failures the capture parks instead of reconnecting forever.
     */
    private static final int MAX_HANDLER_FAILURES = 5;

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
    private final com.qqmu.jync.service.fanout.FanoutRuntimeRegistry registry;
    private final AutoSyncService autoSync;
    private final SyncStateWriter stateWriter;

    private volatile boolean running = true;
    private volatile boolean rebaselineRequested;
    private volatile BinaryLogClient client;
    private final LazyLobSpillStore lobSpillStore = new LazyLobSpillStore();
    private final AtomicInteger handlerFailures = new AtomicInteger();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

    public FanoutCdcConsumer(Project project, SyncLockService.LockHandle lock, SyncContext ctx,
                             BinlogClientFactory clientFactory, CdcPositionStore positionStore,
                             CdcBaselineService baselineService, SyncTaskStore taskStore,
                             CdcRuntimeStats stats, SyncProperties properties,
                             FanoutRuntimeFactory runtimes,
                             com.qqmu.jync.service.fanout.FanoutRuntimeRegistry registry,
                             AutoSyncService autoSync, SyncStateWriter stateWriter) {
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
        this.registry = registry;
        this.autoSync = autoSync;
        this.stateWriter = stateWriter;
    }

    @Override
    public void run() {
        long projectId = project.getId();
        FanoutRuntime runtime = null;
        try {
            // Inside the try: a metadata failure here must not leave the caller's lease
            // handle open with an uncaught exception (the single-target path was fixed the
            // same way).
            taskStore.markCdcRunning(projectId);
            runtime = runtimes.create(project, ctx, "fanout-" + projectId, lock::renew, stats);
            runtime.startWorkers();
            // Registered like the engine-backed fan-out consumers: an administrator DDL
            // reaches this runtime through the registry and drops the workers' cached
            // column layouts.
            registry.register(projectId, runtime);
            if (runtime.needsRebaseline()) {
                // A target's queue overflowed in an earlier run: the rows it missed are no
                // longer in any spool. Forget the shared anchor and reload every target.
                log.warn("Fan-out project {} dropped events for a target earlier;"
                        + " re-running the baseline", projectId);
                positionStore.delete(projectId);
            }
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
            registry.remove(projectId, runtime);
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
        runtime.clearRebaselineMarks();
        log.info("Fan-out baseline for project {} finished", projectId);
    }

    /**
     * Adds source tables that appeared after the auto-sync switch was enabled. A fan-out
     * re-baseline is the safe first version: the new table has no history on any target, and
     * every target must receive it before the shared stream resumes.
     */
    /** Keyless tables are never added; disconnecting for them would loop on the CREATE. */
    private boolean autoSyncEligible(String db, String table) {
        try (java.sql.Connection conn = openSource()) {
            String schema = db == null || db.isBlank() ? ctx.getSourceSchema() : db;
            com.qqmu.jync.dto.meta.TableMeta meta =
                    ctx.getSourceReader().readTable(conn, schema, table);
            return meta != null && meta.hasPrimaryKey();
        } catch (Exception e) {
            log.debug("Auto-sync: cannot check {}: {}", table, e.toString());
            return false;
        }
    }

    private void reconcileAutoSync(long projectId) {
        if (!ctx.getConfig().isAutoSyncNewTables()) {
            return;
        }
        try (java.sql.Connection conn = openSource()) {
            java.util.List<String> sourceTables = ctx.getSourceReader()
                    .listTableNames(conn, ctx.getSourceSchema());
            // Only keyed tables: the fan-out baseline skips a keyless table (CDC cannot
            // address its rows), yet a table added to the selection would stay there and be
            // reported as skipped on every baseline — the single-target reconcile applies the
            // same primary-key filter.
            java.util.List<String> added = autoSync.reconcile(project, ctx.getConfig(),
                    sourceTables, name -> {
                        try {
                            var table = ctx.getSourceReader()
                                    .readTable(conn, ctx.getSourceSchema(), name);
                            return table != null && table.hasPrimaryKey();
                        } catch (java.sql.SQLException e) {
                            log.debug("Auto-sync: cannot read metadata for {}: {}",
                                    name, e.getMessage());
                            return false;
                        }
                    });
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
            // A published transaction proves the handler is making progress again.
            handlerFailures.set(0);
            // The inner stream loop can run for hours without reaching its own lease check;
            // an expired/force-released lease must stop this node before it advances the
            // shared anchor, or a takeover node would stream the same window in parallel.
            ctx.requireLease();
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
            FanoutMetaSupplier supplier = new FanoutMetaSupplier(
                    new com.qqmu.jync.service.fanout.FanoutBindingResolver(ctx));
            CdcEventStreamHandler handler = new CdcEventStreamHandler(
                    ctx.getConfig(), ctx.getSourceSchema(), supplier, new CdcRowMapper(), stats,
                    current::getGtidSet, sink, lobSpillStore.get(properties),
                    properties.getLob().getCdcSpillThresholdBytes(),
                    CdcTransactionBudget.from(properties),
                    (db, sql, file, position) -> stateWriter.recordSkippedDdl(
                            projectId, db, sql, file, position),
                    null,
                    (db, table, sql, file, position) -> {
                        if (!ctx.getConfig().isAutoSyncNewTables()) {
                            return;
                        }
                        if (!autoSyncEligible(db, table)) {
                            log.info("Fan-out capture: new table {}.{} has no primary key;"
                                    + " auto-sync keeps streaming without it", db, table);
                            return;
                        }
                        rebaselineRequested = true;
                        log.info("Fan-out capture saw CREATE TABLE {}.{}; reconnecting to"
                                + " re-baseline", db, table);
                        disconnect(current);
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
            lastFailure.set(null);
            current.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
                @Override
                public void onConnect(BinaryLogClient client) {
                    stats.setConnected(true);
                    stats.markStarted();
                }

                @Override
                public void onCommunicationFailure(BinaryLogClient client, Exception e) {
                    lastFailure.compareAndSet(null, e);
                }

                @Override
                public void onEventDeserializationFailure(BinaryLogClient client, Exception e) {
                    lastFailure.compareAndSet(null, e);
                    if (handlerFailures.incrementAndGet() > MAX_HANDLER_FAILURES) {
                        // The client would otherwise stream past poison events; force the
                        // drop so the loop below can park instead of advancing past them.
                        log.error("Fan-out capture for project {} hit {} consecutive"
                                + " deserialization failures; dropping the connection",
                                projectId, handlerFailures.get());
                        disconnect(current);
                    }
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
            }
            if (!running || rebaselineRequested) {
                break;
            }
            // The handler stores the real cause and disconnects itself; without reading it
            // here a schema-drift / poison-event / publish failure looked like a clean drop
            // and the capture reconnected forever without ever surfacing an error.
            Throwable handlerFailure = handler.takeFailure();
            if (handlerFailure != null) {
                handlerFailures.incrementAndGet();
            }
            Throwable failure = handlerFailure != null ? handlerFailure : lastFailure.get();
            if (failure != null) {
                CdcFailureClassifier.Kind kind = CdcFailureClassifier.classify(failure);
                stats.setLastError(CdcFailureClassifier.describe(failure));
                if (kind != CdcFailureClassifier.Kind.TRANSIENT
                        || handlerFailures.get() > MAX_HANDLER_FAILURES) {
                    // Rethrow so run() applies the normal fatal/transient bookkeeping
                    // (recordCdcFailure + ERROR hat). The cause chain keeps the classifier's
                    // original verdict intact.
                    throw new IllegalStateException("fan-out capture stopped: "
                            + CdcFailureClassifier.describe(failure), failure);
                }
            }
            long delay = backoff.nextDelayMs();
            if (failure == null) {
                log.info("Fan-out capture for project {} disconnected cleanly, retrying in"
                        + " {} ms", projectId, delay);
            } else {
                log.warn("Fan-out capture for project {} disconnected ({}), retrying in {} ms",
                        projectId, CdcFailureClassifier.describe(failure), delay);
            }
            if (!CdcWaits.sleepWhileRunning(() -> running, delay)) {
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
