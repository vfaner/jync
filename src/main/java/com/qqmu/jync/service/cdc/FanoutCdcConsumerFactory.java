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
    private final AutoSyncService autoSync;

    public FanoutCdcConsumerFactory(BinlogClientFactory clientFactory,
                                    CdcPositionStore positionStore,
                                    CdcBaselineService baselineService,
                                    SyncTaskStore taskStore, SyncProperties properties,
                                    FanoutRuntimeFactory runtimes, AutoSyncService autoSync) {
        this.clientFactory = clientFactory;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.properties = properties;
        this.runtimes = runtimes;
        this.autoSync = autoSync;
    }

    public ManagedCdcConsumer create(Project project, SyncLockService.LockHandle lock,
                                     SyncContext ctx, CdcRuntimeStats stats) {
        return new FanoutCdcConsumer(project, lock, ctx, clientFactory, positionStore,
                baselineService, taskStore, stats, properties, runtimes, autoSync);
    }
}
