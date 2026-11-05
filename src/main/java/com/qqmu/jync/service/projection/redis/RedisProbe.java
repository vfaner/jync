package com.qqmu.jync.service.projection.redis;

/**
 * The read-only command surface a Redis precheck needs.
 *
 * <p>Deliberately narrow: a precheck issues PING, CLUSTER INFO and CONFIG GET, never a write.
 * The write path has its own sink ({@link RedisCommandSink}) because the two have opposite
 * requirements — this one is short-lived and synchronous, the sink is pooled and pipelined.
 */
public interface RedisProbe extends AutoCloseable {

    String ping();

    /** Raw {@code CLUSTER INFO} body; only meaningful for a cluster target. */
    String clusterInfo();

    /** Current {@code maxmemory-policy}, or null when the server did not report one. */
    String maxmemoryPolicy();

    @Override
    void close();
}
