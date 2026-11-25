package com.qqmu.jync.service.fanout;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.projection.redis.RedisUris;
import com.qqmu.jync.util.CryptoUtil;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops the Redis Stream a deleted project left behind.
 *
 * <p>The spool transport owns a directory, removed by name on delete. With the Redis
 * transport the stream key is the project's only external state: leaving it would leak one
 * stream (and its consumer groups) per deleted project, and a later project reusing the id
 * would inherit events it never produced. Deleting is best-effort — a Redis blip must not
 * fail the project deletion the user asked for.
 *
 * <p>The stream lives on the Redis <em>target</em> the project selected, not on any
 * server-wide default; a project saved before the per-project picker existed still falls
 * back to {@code sync.fanout.redis-uri}.
 */
@Slf4j
@Service
public class FanoutStreamCleanup {

    static final String STREAM_KEY_PREFIX = "jync:fanout:";
    static final String STREAM_KEY_SUFFIX = ":events";

    private final SyncProperties properties;
    private final RedisTargetService redisTargets;
    private final CryptoUtil cryptoUtil;

    public FanoutStreamCleanup(SyncProperties properties, RedisTargetService redisTargets,
                               CryptoUtil cryptoUtil) {
        this.properties = properties;
        this.redisTargets = redisTargets;
        this.cryptoUtil = cryptoUtil;
    }

    public void delete(Project project, SyncConfig config) {
        if (project == null || project.getId() == null) {
            return;
        }
        String transport = config == null ? null : config.getFanoutTransport();
        if (!"REDIS".equalsIgnoreCase(transport)
                && properties.getFanout().getTransport()
                        != SyncProperties.Fanout.Transport.REDIS) {
            return;
        }
        Long projectId = project.getId();
        RedisClient client = null;
        try {
            RedisURI uri = redisUri(config);
            if (uri == null) {
                log.warn("No Redis endpoint resolves for the fan-out stream of project {};"
                        + " its stream key {} must be removed manually", projectId,
                        streamKey(projectId));
                return;
            }
            // Deletion is a one-shot best-effort call; a dead Redis must not hold the
            // deleting thread for the client's default connect timeout.
            uri.setTimeout(Duration.ofSeconds(3));
            client = RedisClient.create(uri);
            try (StatefulRedisConnection<String, String> connection =
                         client.connect(io.lettuce.core.codec.StringCodec.UTF8)) {
                Long removed = connection.sync().del(streamKey(projectId));
                log.info("Deleted fan-out Redis stream {} for project {} ({} key(s))",
                        streamKey(projectId), projectId, removed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete the fan-out Redis stream of project {}: {}",
                    projectId, e.toString());
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /** The project's selected Redis target first, the server-wide fallback second. */
    private RedisURI redisUri(SyncConfig config) {
        Long targetId = config == null ? null : config.getFanoutRedisTargetId();
        RedisTarget target = targetId == null ? null
                : redisTargets.find(targetId).orElse(null);
        if (target != null) {
            String password = target.getPasswordEnc() == null ? null
                    : cryptoUtil.decrypt(target.getPasswordEnc());
            return RedisUris.from(target, password);
        }
        String uri = properties.getFanout().getRedisUri();
        return uri == null || uri.isBlank() ? null : RedisURI.create(uri);
    }

    static String streamKey(Long projectId) {
        return STREAM_KEY_PREFIX + projectId + STREAM_KEY_SUFFIX;
    }
}
