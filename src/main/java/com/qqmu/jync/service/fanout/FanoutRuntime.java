package com.qqmu.jync.service.fanout;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

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
    private final List<FanoutTargetWorker> workers;
    private final FanoutBaselineService baselineService;

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
        this.workers = new ArrayList<>();
        this.baselineService = new FanoutBaselineService(structureSync, progressStore);
        for (FanoutBaselineService.Target target : targets) {
            String group = String.valueOf(target.targetDbId());
            EventTransport.Subscription subscription = transport.subscribe(group);
            EventApplier applier = new JdbcFanoutApplier(target.context(), stats, bindings);
            workers.add(new FanoutTargetWorker(group, subscription, applier,
                    position -> recordTargetPosition(positionStore, sourceCtx.projectId(),
                            target.targetDbId(), position)));
        }
    }

    /** Must run before the baseline so published rows are consumed immediately. */
    public void startWorkers() {
        workers.forEach(FanoutTargetWorker::start);
    }

    public void baseline() throws Exception {
        baselineService.baseline(sourceCtx, sourceConn, targets, transport);
    }

    public EventTransport transport() {
        return transport;
    }

    @Override
    public void close() {
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

    private static void recordTargetPosition(FanoutPositionStore store, Long projectId,
                                             Long targetDbId, String position) {
        if (position == null || position.startsWith("baseline:")) {
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
