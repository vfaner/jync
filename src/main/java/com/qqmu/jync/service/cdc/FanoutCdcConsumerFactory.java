package com.qqmu.jync.service.cdc;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.fanout.FanoutRuntimeFactory;
import com.qqmu.jync.service.sync.AutoSyncService;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/** Builds the fan-out consumer from the manager's already-resolved project context. */
@Service
public class FanoutCdcConsumerFactory {

    private final BinlogClientFactory clientFactory;
    private final CdcPositionStore positionStore;
    private final CdcBaselineService baselineService;
    private final SyncTaskStore taskStore;
    private final SyncProperties properties;
    private final FanoutRuntimeFactory runtimes;
    private final com.qqmu.jync.service.fanout.FanoutRuntimeRegistry registry;
    private final AutoSyncService autoSync;

    public FanoutCdcConsumerFactory(BinlogClientFactory clientFactory,
                                    CdcPositionStore positionStore,
                                    CdcBaselineService baselineService,
                                    SyncTaskStore taskStore, SyncProperties properties,
                                    FanoutRuntimeFactory runtimes,
                                    com.qqmu.jync.service.fanout.FanoutRuntimeRegistry registry,
                                    AutoSyncService autoSync) {
        this.clientFactory = clientFactory;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.properties = properties;
        this.runtimes = runtimes;
        this.registry = registry;
        this.autoSync = autoSync;
    }

    public ManagedCdcConsumer create(Project project, SyncLockService.LockHandle lock,
                                     SyncContext ctx, CdcRuntimeStats stats) {
        return new FanoutCdcConsumer(project, lock, ctx, clientFactory, positionStore,
                baselineService, taskStore, stats, properties, runtimes, autoSync);
    }

    /**
     * Runs an existing JDBC engine (PG logical / LogMiner / SQL Server / YStream) as the
     * capture side of a fan-out project. The engine keeps its stream and source-position
     * logic; the runtime owns every target.
     */
    public ManagedCdcConsumer wrapEngine(Project project, SyncLockService.LockHandle lock,
                                         SyncContext ctx, CdcRuntimeStats stats,
                                         ManagedCdcConsumer engine) {
        return new com.qqmu.jync.service.fanout.FanoutEngineConsumer(project, engine, registry,
                taskStore, stats, () -> {
                    com.qqmu.jync.service.fanout.FanoutRuntime runtime = runtimes.create(
                            project, ctx, "fanout-" + project.getId(), lock::renew, stats);
                    runtime.startWorkers();
                    return runtime;
                });
    }
}
