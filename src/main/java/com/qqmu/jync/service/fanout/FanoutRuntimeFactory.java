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
    private final com.qqmu.jync.service.projection.redis.RedisTargetService redisTargets;
    private final com.qqmu.jync.util.CryptoUtil cryptoUtil;

    public FanoutRuntimeFactory(FanoutTargetContextFactory targetContexts,
                                StructureSyncService structureSync,
                                FanoutProgressStore progressStore,
                                FanoutPositionStore positionStore,
                                com.qqmu.jync.service.cdc.CdcTargetRepairService targetRepair,
                                ObjectMapper mapper, SyncProperties properties,
                                com.qqmu.jync.service.projection.redis.RedisTargetService
                                        redisTargets,
                                com.qqmu.jync.util.CryptoUtil cryptoUtil) {
        this.targetContexts = targetContexts;
        this.structureSync = structureSync;
        this.progressStore = progressStore;
        this.positionStore = positionStore;
        this.targetRepair = targetRepair;
        this.mapper = mapper;
        this.properties = properties;
        this.redisTargets = redisTargets;
        this.cryptoUtil = cryptoUtil;
    }

    public FanoutRuntime create(Project project, SyncContext sourceCtx, String lockOwner,
                                BooleanSupplier lockRenewer, CdcRuntimeStats stats)
            throws Exception {
        Set<Long> targetIds = new LinkedHashSet<>();
        if (project.getTargetDbId() != null) {
            targetIds.add(project.getTargetDbId());
        }
        if (sourceCtx.getConfig() != null
                && sourceCtx.getConfig().getFanoutTargetDbIds() != null) {
            targetIds.addAll(sourceCtx.getConfig().getFanoutTargetDbIds());
        }
        List<FanoutBaselineService.Target> targets = new ArrayList<>();
        for (Long targetId : targetIds) {
            targets.add(new FanoutBaselineService.Target(targetId,
                    targetContexts.build(project, targetId, lockOwner, lockRenewer)));
        }
        EventTransport transport;
        SyncProperties.Fanout.Transport kind = properties.getFanout().getTransport();
        String configured = sourceCtx.getConfig() == null ? null
                : sourceCtx.getConfig().getFanoutTransport();
        if ("REDIS".equalsIgnoreCase(configured)) {
            kind = SyncProperties.Fanout.Transport.REDIS;
        } else if ("SPOOL".equalsIgnoreCase(configured)) {
            kind = SyncProperties.Fanout.Transport.SPOOL;
        }
        if (kind == SyncProperties.Fanout.Transport.REDIS) {
            Long targetId = sourceCtx.getConfig() == null ? null
                    : sourceCtx.getConfig().getFanoutRedisTargetId();
            com.qqmu.jync.model.RedisTarget target = targetId == null ? null
                    : redisTargets.find(targetId).orElse(null);
            String streamKey = "jync:fanout:" + project.getId() + ":events";
            if (target != null) {
                // Same entity (and encrypted password) the cache projection uses: a jar
                // deployment needs no extra server-side URI for the event transport.
                String password = target.getPasswordEnc() == null ? null
                        : cryptoUtil.decrypt(target.getPasswordEnc());
                transport = new RedisStreamEventTransport(
                        com.qqmu.jync.service.projection.redis.RedisUris.from(target, password),
                        streamKey, properties.getFanout().getRedisMaxLen());
            } else if (targetId != null) {
                // A selected target that no longer resolves must NOT fall back to the
                // server-wide URI: the events would move to an unrelated Redis (or the
                // default localhost) without anyone noticing.
                throw new IllegalStateException("Fan-out Redis transport for project "
                        + project.getId() + " points at Redis target " + targetId
                        + " which no longer exists; pick another target or switch to the"
                        + " local queue");
            } else {
                // Legacy: a server-wide Redis URI still works for projects saved before the
                // per-project target picker existed.
                String uri = properties.getFanout().getRedisUri();
                if (uri == null || uri.isBlank()) {
                    throw new IllegalStateException("Fan-out Redis transport for project "
                            + project.getId() + " has no Redis target selected");
                }
                transport = new RedisStreamEventTransport(io.lettuce.core.RedisURI.create(uri),
                        streamKey, properties.getFanout().getRedisMaxLen());
            }
        } else {
            Path spool = Path.of("data", "fanout", String.valueOf(project.getId()));
            transport = new SpoolingEventTransport(spool, SPOOL_MAX_BYTES, mapper);
        }
        // Every target gets the same missing-table repair as a single-target consumer; the
        // service's attempt budget is per project, which is shared by all targets.
        return new FanoutRuntime(sourceCtx, targets, stats, structureSync, progressStore,
                positionStore, targetRepair, properties.getQueryTimeoutSeconds(), transport,
                properties::getBatchSize);
    }
}
