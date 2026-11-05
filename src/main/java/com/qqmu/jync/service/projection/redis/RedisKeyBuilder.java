package com.qqmu.jync.service.projection.redis;

import java.util.List;
import java.util.Map;

/**
 * Builds {@code <prefix><table>:<pk1>[:<pk2>...]} keys.
 *
 * <p>The table is already the mapped target name — the same name a JDBC target would use — and the
 * primary-key components keep the declared column order. The prefix is a project-level contract:
 * several projects may share one Redis, so it must end with {@code :}.
 */
public final class RedisKeyBuilder {

    private final String keyPrefix;

    public RedisKeyBuilder(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Redis key prefix is required");
        }
        this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
    }

    public String key(String table, List<String> pkColumns, Map<String, Object> row) {
        StringBuilder key = new StringBuilder(keyPrefix).append(table);
        for (String column : pkColumns) {
            Object value = row.get(column);
            if (value == null) {
                throw new IllegalArgumentException(
                        "primary key component '" + column + "' is null for table " + table);
            }
            key.append(':').append(RedisValues.keyComponent(value));
        }
        return key.toString();
    }
}
