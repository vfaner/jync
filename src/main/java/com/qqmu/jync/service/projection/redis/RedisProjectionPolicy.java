package com.qqmu.jync.service.projection.redis;

import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.CaptureStrategyParser;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * The server-side rules for a project whose target is Redis.
 *
 * <p>Kept out of {@code ProjectService} on purpose: a database project's save path should not
 * grow a branch for every cache rule, and the rules are only meaningful together — a Redis
 * target without a log-CDC strategy writes nothing, and two projects on one target sharing a
 * prefix silently overwrite each other's keys. {@code ProjectService} calls two methods.
 *
 * <p>All refusals are bare i18n keys; the controller renders them like every other save error.
 */
@Component
public class RedisProjectionPolicy {

    /** Smallest per-row value budget worth configuring; below this almost every row is skipped. */
    static final int MIN_MAX_VALUE_BYTES = 4 * 1024;

    /** Ceiling for one projected value; a larger row belongs in blob storage, not a cache. */
    static final int MAX_MAX_VALUE_BYTES = 16 * 1024 * 1024;

    private final RedisTargetRepository targets;
    private final ProjectRepository projects;
    private final SyncContextFactory contextFactory;

    public RedisProjectionPolicy(RedisTargetRepository targets, ProjectRepository projects,
                                 SyncContextFactory contextFactory) {
        this.targets = targets;
        this.projects = projects;
        this.contextFactory = contextFactory;
    }

    /** A Redis project's target must be a saved row, not just a non-null id. */
    public void requireTarget(Project project) {
        Long targetId = project.getTargetRedisId();
        if (targetId == null) {
            throw new IllegalArgumentException("error.project.redis.target.required");
        }
        if (!targets.existsById(targetId)) {
            throw new IllegalArgumentException("error.redis.target.not.found");
        }
    }

    /**
     * A source that cannot produce change events can never feed a cache projection. The
     * strategy list is asked of the same parser the save path uses, so the answer cannot
     * drift from what {@link CaptureStrategyParser#parseRequested} would accept.
     */
    public void requireCdcCapableSource(DatabaseConfig source) {
        if (source == null) {
            // The endpoint could not be resolved; the save path already refuses a missing
            // source where it matters, and guessing "no CDC" here would mislabel it.
            return;
        }
        boolean capable = CaptureStrategyParser
                .supportedFor(source.getType(), source.resolvedFamily()).stream()
                .anyMatch(CaptureStrategy::isLogCdc);
        if (!capable) {
            throw new IllegalArgumentException("error.project.redis.sourceNoCdc");
        }
    }

    /**
     * Validates and normalizes the projection contract, then forces the switches a cache
     * target does not have.
     *
     * @param effective the strategy the project will run — a submitted one, or the stored one
     *                  when the caller omitted it (the column-rule and AI save paths do)
     */
    public RedisProjectionConfig apply(Project project, SyncConfig config,
                                       CaptureStrategy effective, DatabaseConfig source) {
        if (effective == null || !effective.isLogCdc()) {
            throw new IllegalArgumentException("error.project.redis.cdcOnly");
        }
        requireCdcCapableSource(source);

        RedisProjectionConfig projection = config.getRedisProjection();
        if (projection == null) {
            projection = RedisProjectionConfig.defaultFor(project.getId());
            config.setRedisProjection(projection);
        }
        projection.setKeyPrefix(normalizePrefix(projection.getKeyPrefix(), project.getId()));
        if (projection.getValueFormat() == null) {
            projection.setValueFormat(RedisValueFormat.JSON);
        }
        if (projection.getExcludedColumns() == null) {
            projection.setExcludedColumns(new LinkedHashSet<>());
        }
        if (projection.getTtlSeconds() < 0) {
            throw new IllegalArgumentException("error.project.redis.ttl.invalid");
        }
        if (projection.getMaxValueBytes() < MIN_MAX_VALUE_BYTES
                || projection.getMaxValueBytes() > MAX_MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("error.project.redis.maxValueBytes.invalid");
        }
        if (projection.getMaxCommandsPerTransaction() <= 0) {
            throw new IllegalArgumentException("error.project.redis.maxCommands.invalid");
        }
        rejectPrefixConflict(project, projection.getKeyPrefix());

        // A cache has no DDL and no views/routines; only rows travel. Forcing the flags
        // rather than refusing them means a crafted POST cannot store a config that makes
        // the engine look for a target database that does not exist.
        config.setSyncData(true);
        config.setSyncStructure(false);
        config.setSyncIndexes(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        return projection;
    }

    /** A project whose target is a database has no cache projection; drop any stale one. */
    public void clear(SyncConfig config) {
        config.setRedisProjection(null);
    }

    private void rejectPrefixConflict(Project project, String prefix) {
        if (conflictingProject(project, prefix) != null) {
            throw new IllegalArgumentException("error.project.redis.prefixConflict");
        }
    }

    /**
     * The other project on this Redis target already claiming {@code prefix}, or null.
     *
     * <p>Shared with the precheck: a save refuses the conflict, and a report has to name the
     * project that owns the keys. Blank or missing identity means "not saved yet", which cannot
     * conflict with anything.
     */
    public Project conflictingProject(Project project, String prefix) {
        if (project == null || project.getId() == null || project.getTargetRedisId() == null
                || prefix == null) {
            return null;
        }
        List<Project> siblings = projects.findByTargetRedisId(project.getTargetRedisId());
        if (siblings == null) {
            return null;
        }
        for (Project other : siblings) {
            if (other == null || other.getId() == null || other.getId().equals(project.getId())) {
                continue;
            }
            RedisProjectionConfig otherProjection =
                    contextFactory.parseConfig(other).getRedisProjection();
            if (otherProjection == null) {
                continue;
            }
            String otherPrefix = normalizePrefix(otherProjection.getKeyPrefix(), other.getId());
            if (prefix.equals(otherPrefix)) {
                return other;
            }
        }
        return null;
    }

    /**
     * A prefix always ends with ':' and is never blank. A blank value means "use the default"
     * rather than an error: the project was just created and the default is the safe one.
     */
    public static String normalizePrefix(String raw, Long projectId) {
        String prefix = raw == null ? "" : raw.trim();
        if (prefix.isEmpty()) {
            prefix = "jync:" + projectId + ":";
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
