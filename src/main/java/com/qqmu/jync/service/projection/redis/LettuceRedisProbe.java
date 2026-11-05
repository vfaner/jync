package com.qqmu.jync.service.projection.redis;

import java.util.Map;

import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

/**
 * A {@link RedisProbe} over one Lettuce connection.
 *
 * <p>{@link io.lettuce.core.api.sync.RedisCommands} extends {@link RedisClusterCommands}, so one
 * type serves both a standalone and a cluster connection; {@code clusterInfo()} is only called
 * for a target that is actually a cluster.
 */
final class LettuceRedisProbe implements RedisProbe {

    private final RedisClusterCommands<String, String> commands;
    private final Runnable closer;

    LettuceRedisProbe(RedisClusterCommands<String, String> commands, Runnable closer) {
        this.commands = commands;
        this.closer = closer;
    }

    @Override
    public String ping() {
        return commands.ping();
    }

    @Override
    public String clusterInfo() {
        return commands.clusterInfo();
    }

    @Override
    public String maxmemoryPolicy() {
        Map<String, String> values = commands.configGet("maxmemory-policy");
        return values == null ? null : values.get("maxmemory-policy");
    }

    @Override
    public void close() {
        closer.run();
    }
}
