package com.qqmu.jync.service.fanout;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;

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
 */
@Slf4j
@Service
public class FanoutStreamCleanup {

    static final String STREAM_KEY_PREFIX = "jync:fanout:";
    static final String STREAM_KEY_SUFFIX = ":events";

    private final SyncProperties properties;

    public FanoutStreamCleanup(SyncProperties properties) {
        this.properties = properties;
    }

    public void delete(Long projectId) {
        if (projectId == null || properties.getFanout().getTransport()
                != SyncProperties.Fanout.Transport.REDIS) {
            return;
        }
        String uri = properties.getFanout().getRedisUri();
        RedisClient client = null;
        try {
            RedisURI redisUri = RedisURI.create(uri);
            // Deletion is a one-shot best-effort call; a dead Redis must not hold the
            // deleting thread for the client's default connect timeout.
            redisUri.setTimeout(Duration.ofSeconds(3));
            client = RedisClient.create(redisUri);
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

    static String streamKey(Long projectId) {
        return STREAM_KEY_PREFIX + projectId + STREAM_KEY_SUFFIX;
    }
}
