package com.qqmu.jync.service.projection.redis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One command a row projection needs: {@code SET}, {@code HSET} (plus the fields that vanished and
 * must be {@code HDEL}ed) or {@code DEL}, with an optional TTL applied after the write.
 *
 * <p>Transactions accumulate these and hand the whole list to {@link RedisProjectionWriter#apply};
 * nothing here talks to Redis.
 */
public record RedisMutation(Kind kind, String key, Map<String, String> fields, String jsonValue,
                            List<String> deleteFields, long ttlSeconds) {

    public enum Kind {
        SET,
        HSET,
        DEL
    }

    public RedisMutation {
        fields = fields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        deleteFields = deleteFields == null ? List.of() : List.copyOf(deleteFields);
    }

    public boolean hasTtl() {
        return ttlSeconds > 0;
    }
}
