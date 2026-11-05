package com.qqmu.jync.service.projection.redis;

/** How one source row is laid out in Redis: one JSON document or one hash of fields. */
public enum RedisValueFormat {
    JSON,
    HASH
}
