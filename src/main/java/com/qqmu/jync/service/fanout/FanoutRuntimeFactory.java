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
    private final ObjectMapper mapper;

    public FanoutRuntimeFactory(FanoutTargetContextFactory targetContexts,
                                StructureSyncService structureSync,
                                FanoutProgressStore progressStore,
                                FanoutPositionStore positionStore,
                                ObjectMapper mapper) {
        this.targetContexts = targetContexts;
        this.structureSync = structureSync;
        this.progressStore = progressStore;
        this.positionStore = positionStore;
        this.mapper = mapper;
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
        Path spool = Path.of("data", "fanout", String.valueOf(project.getId()));
        return new FanoutRuntime(sourceCtx, targets, stats, structureSync, progressStore,
                positionStore, mapper, spool, SPOOL_MAX_BYTES);
    }
}
