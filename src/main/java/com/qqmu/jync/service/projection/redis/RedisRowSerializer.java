package com.qqmu.jync.service.projection.redis;

import java.util.Map;

/**
 * Turns one source row into the mutation that projects it.
 *
 * <p>An upsert returns {@code null} when the row cannot be projected safely (an unsupported value
 * type or a value over the byte budget). The caller counts and warns; a cache may be incomplete and
 * fall back to the database, so one bad row must never stop the stream.
 */
public interface RedisRowSerializer {

    RedisMutation upsert(String key, Map<String, Object> row);

    /** A delete needs only the key; the row's values never travel in the mutation. */
    RedisMutation delete(String key);
}
