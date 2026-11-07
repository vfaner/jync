package com.qqmu.jync.service.projection.redis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.cdc.CdcBaselineService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * One-click rebuild of a project's cache: optionally sweep the project's own keys, re-anchor the
 * stream, then project every selected table again.
 *
 * <p>Only a stopped project may be rebuilt. A running consumer would keep writing mutations
 * derived from the old position while the baseline rewrites the same keys, and the interleaving
 * has no ordering guarantee — the cache would be a mix of both.
 *
 * <p>The anchor is reset before the sweep, not after the baseline. Should anything fail in
 * between, {@code baselineDone} is then false and the next start reruns the whole baseline; the
 * alternative leaves a true flag on a cache some of whose keys were just deleted, and the
 * consumer would happily stream on top of the hole. The row itself survives so the key-space
 * contract of any keys the sweep failed to delete is still known to start and precheck.
 */
@Slf4j
@Service
public class RedisRebuildService {

    /** The synthetic owner used for a rebuild's context; no lease is held, so it is never lost. */
    private static final String REBUILD_LOCK_OWNER = "redis-rebuild";

    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final SyncContextFactory contextFactory;
    private final RedisTargetService targets;
    private final RedisBaselineService baseline;
    private final CdcBaselineService cdcBaseline;
    private final CdcPositionStore positionStore;
    private final RedisPrefixSweeper sweeper;
    private final AdminAuditService auditService;
    private final SyncTaskStore taskStore;
    private final RedisProjectionPolicy policy;

    public RedisRebuildService(ProjectRepository projects, ProjectService projectService,
                               SyncContextFactory contextFactory, RedisTargetService targets,
                               RedisBaselineService baseline, CdcBaselineService cdcBaseline,
                               CdcPositionStore positionStore, RedisPrefixSweeper sweeper,
                               AdminAuditService auditService, SyncTaskStore taskStore,
                               RedisProjectionPolicy policy) {
        this.projects = projects;
        this.projectService = projectService;
        this.contextFactory = contextFactory;
        this.targets = targets;
        this.baseline = baseline;
        this.cdcBaseline = cdcBaseline;
        this.positionStore = positionStore;
        this.sweeper = sweeper;
        this.auditService = auditService;
        this.taskStore = taskStore;
        this.policy = policy;
    }

    /**
     * @param sweepPrefix also delete every key under the project's prefix first; without it the
     *                    baseline overwrites the keys it projects and leaves unrelated leftovers
     *                    (rows deleted from the source long ago) in place
     * @throws IllegalArgumentException with {@code error.project.not.found} or
     *         {@code error.project.redis.rebuild.notRedis}
     * @throws IllegalStateException with {@code error.project.redis.rebuild.mustStop} while the
     *         project is enabled or a live consumer holds its lease
     */
    public RebuildResult rebuild(Long projectId, boolean sweepPrefix) {
        long startedAt = System.currentTimeMillis();
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        // The kind check comes first: "stop the project first" is advice for a cache project,
        // and a database project cannot be rebuilt no matter what state it is in. A stale
        // form that still shows the cache card after the target was switched reaches here.
        if (project.getTargetKindOrDefault() != SyncTargetKind.REDIS) {
            throw new IllegalArgumentException("error.project.redis.rebuild.notRedis");
        }
        if (projectService.isRunning(project)) {
            throw new IllegalStateException("error.project.redis.rebuild.mustStop");
        }
        SyncContext ctx = contextFactory.buildForRedis(project, REBUILD_LOCK_OWNER,
                () -> true, null);
        RedisProjectionConfig projection = requireProjection(ctx);
        // The sweep below deletes every key under this prefix, and the baseline rewrites the
        // rest. The settings save refuses a shared prefix, but a projection can also arrive by
        // a switch back to Redis, a clone, or a direct database edit — none of which ran that
        // check. Rebuilding in another project's namespace would delete keys this project never
        // wrote, so refuse before anything is dropped or swept.
        String currentPrefix = RedisProjectionPolicy.normalizePrefix(
                projection.getKeyPrefix(), project.getId());
        if (policy.conflictingProject(project, currentPrefix) != null) {
            throw new IllegalStateException("error.project.redis.prefixConflict");
        }
        // What the keys in Redis were actually written with, before the position is dropped.
        String builtSignature = positionStore.find(projectId)
                .map(CdcPosition::getProjectionSignature)
                .orElse(null);
        String signature = RedisProjectionSignature.of(projection);
        if (builtSignature != null && signature != null && !builtSignature.equals(signature)
                && !sweepPrefix) {
            // A value-format switch leaves keys of the old type: SET over a hash (or HSET
            // over a string) is WRONGTYPE, and a baseline without the sweep rewrites every key
            // it projects. There is no in-place conversion, so the sweep is the only correct
            // rebuild of a changed contract.
            throw new IllegalStateException("error.project.redis.rebuild.sweepRequired");
        }

        // Reset, not delete: the row also carries the contract of the keys still in Redis. If
        // the sweep or the baseline fails halfway, that contract is the only record of what
        // type the leftover keys have — and start/precheck refuse a format change against it
        // instead of letting the next baseline answer WRONGTYPE. A completed baseline
        // replaces it with what it actually wrote.
        positionStore.resetAnchorKeepingProjectionSignature(projectId);

        long sweptKeys = 0;
        if (sweepPrefix) {
            RedisTarget target = requireTarget(project);
            for (String prefix : sweepPrefixes(project, builtSignature,
                    projection.getKeyPrefix())) {
                sweptKeys += sweeper.sweep(target, prefix);
            }
            log.info("Redis rebuild of project {} swept {} key(s) under '{}'",
                    projectId, sweptKeys, projection.getKeyPrefix());
        }

        RedisBaselineService.BaselineResult result =
                baseline.run(ctx, () -> true, cdcBaseline::recordBaselineAnchor);
        // A parked ERROR hat describes the run before this rebuild; the cache now holds every
        // row the source had, so the task goes back to a clean stopped state.
        taskStore.markStoppedClearingResult(projectId);
        auditService.record(AuditAction.REDIS_REBUILD,
                AuditDetail.of("audit.detail.redisRebuild", String.valueOf(projectId),
                        String.valueOf(result.tables()), String.valueOf(result.rows()),
                        String.valueOf(sweptKeys)));
        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("Redis rebuild of project {} completed: {} table(s), {} row(s), {} key(s)"
                + " swept in {} ms", projectId, result.tables(), result.rows(), sweptKeys,
                elapsedMs);
        return new RebuildResult(result.tables(), result.rows(), sweptKeys, elapsedMs);
    }

    /**
     * The prefixes one sweep must clear: the one the existing keys were built under (when the
     * contract changed) and the current one. A prefix change alone never hits WRONGTYPE, but
     * without the old prefix the whole previous namespace would stay in Redis forever.
     *
     * <p>The old prefix is skipped when another project currently claims it. The save-time
     * conflict check only compares current configurations, so releasing a prefix and letting
     * another project take it is legitimate — and the confirm dialog only promises to delete
     * this project's keys. The signature cannot tell whose keys actually sit under the old
     * prefix, so deleting nothing is the only safe answer.
     */
    private List<String> sweepPrefixes(Project project, String builtSignature,
                                       String currentPrefix) {
        List<String> prefixes = new ArrayList<>(2);
        String previous = RedisProjectionSignature.prefixOf(builtSignature);
        if (previous != null && !previous.equals(currentPrefix)) {
            Project owner = policy.conflictingProject(project, previous);
            if (owner == null) {
                prefixes.add(previous);
            } else {
                log.warn("Not sweeping '{}' while rebuilding project {}: project {} ({}) now"
                                + " uses that prefix; the keys under it are not ours to delete",
                        previous, project.getId(), owner.getId(), owner.getName());
            }
        }
        prefixes.add(currentPrefix);
        return prefixes;
    }

    private RedisTarget requireTarget(Project project) {
        Long targetId = project.getTargetRedisId();
        if (targetId == null) {
            throw new IllegalStateException(
                    "Redis target is not configured for project " + project.getId());
        }
        return targets.find(targetId)
                .orElseThrow(() -> new IllegalStateException(
                        "Redis target " + targetId + " no longer exists"));
    }

    private static RedisProjectionConfig requireProjection(SyncContext ctx) {
        RedisProjectionConfig projection = ctx.getConfig() == null
                ? null : ctx.getConfig().getRedisProjection();
        if (projection == null) {
            throw new IllegalStateException("Redis projection settings are missing for project "
                    + (ctx.getProject() == null ? "?" : ctx.getProject().getId()));
        }
        return projection;
    }

    /** Tables projected, mutations written, keys swept and wall time of one rebuild. */
    public record RebuildResult(int tables, long rows, long sweptKeys, long elapsedMs) {
    }
}
