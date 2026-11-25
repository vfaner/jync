package com.qqmu.jync.service.fanout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.sync.StructureSyncService;
import com.qqmu.jync.service.sync.SyncContext;

import com.qqmu.jync.config.SyncProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Builds a run's target contexts and {@link FanoutRuntime}. Kept separate from the consumer so
 * the runtime (transport + workers + baseline) can be tested and replaced independently.
 */
@Service
public class FanoutRuntimeFactory {

    /** Safety cap for one target's spool; a permanently dead target must be visible. */
    static final long SPOOL_MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private final FanoutTargetContextFactory targetContexts;
    private final StructureSyncService structureSync;
    private final FanoutProgressStore progressStore;
    private final FanoutPositionStore positionStore;
    private final com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair;
    private final ObjectMapper mapper;
    private final SyncProperties properties;

    public FanoutRuntimeFactory(FanoutTargetContextFactory targetContexts,
                                StructureSyncService structureSync,
                                FanoutProgressStore progressStore,
                                FanoutPositionStore positionStore,
                                com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair,
                                ObjectMapper mapper, SyncProperties properties) {
        this.targetContexts = targetContexts;
        this.structureSync = structureSync;
        this.progressStore = progressStore;
        this.positionStore = positionStore;
        this.targetRepair = targetRepair;
        this.mapper = mapper;
        this.properties = properties;
    }

    public FanoutRuntime create(Project project, SyncContext sourceCtx, String lockOwner,
                                BooleanSupplier lockRenewer, CdcRuntimeStats stats)
            throws Exception {
        Set<Long> targetIds = new LinkedHashSet<>();
        if (project.getTargetDbId() != null) {
            targetIds.add(project.getTargetDbId());
        }
        if (project.getSyncConfig() != null) {
            List<Long> extras = sourceCtx.getConfig().getFanoutTargetDbIds();
            if (extras != null) {
                targetIds.addAll(extras);
            }
        }
        List<FanoutBaselineService.Target> targets = new ArrayList<>();
        for (Long targetId : targetIds) {
            targets.add(new FanoutBaselineService.Target(targetId,
                    targetContexts.build(project, targetId, lockOwner, lockRenewer)));
        }
        EventTransport transport;
        if (properties.getFanout().getTransport()
                == SyncProperties.Fanout.Transport.REDIS) {
            transport = new RedisStreamEventTransport(
                    properties.getFanout().getRedisUri(),
                    "jync:fanout:" + project.getId() + ":events");
        } else {
            Path spool = Path.of("data", "fanout", String.valueOf(project.getId()));
            transport = new SpoolingEventTransport(spool, SPOOL_MAX_BYTES, mapper);
        }
        // Every target gets the same missing-table repair as a single-target consumer; the
        // service's attempt budget is per project, which is shared by all targets.
        return new FanoutRuntime(sourceCtx, targets, stats, structureSync, progressStore,
                positionStore, targetRepair, transport);
    }
}
