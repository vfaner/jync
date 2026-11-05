package com.qqmu.jync.service.projection.redis;

import java.util.List;
import java.util.Map;

/**
 * A borrowed, one-transaction-at-a-time command buffer.
 *
 * <p>Commands are recorded, not sent, until {@link #flush()}; that is what lets a whole CDC
 * transaction travel as one pipeline. {@link #close()} always returns the borrowed connection, so
 * the writer releases it even when a reply failed.
 */
public interface RedisCommandSink extends AutoCloseable {

    void set(String key, String value);

    void hset(String key, Map<String, String> fields);

    void hdel(String key, List<String> fields);

    void del(String key);

    void expire(String key, long ttlSeconds);

    /**
     * Sends everything buffered and waits for every reply.
     *
     * @throws Exception when a command failed or the pipeline timed out; the caller must not
     *     advance its position in that case
     */
    void flush() throws Exception;

    @Override
    default void close() {
    }
}
