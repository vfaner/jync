package com.qqmu.jync.service.projection.redis;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The default projection: one key per row, value is the whole row as a JSON object.
 *
 * <p>The document is built by hand, not by Jackson. Serializing an arbitrary {@code Map} through a
 * default {@code ObjectMapper} would guess types (a {@code BigDecimal} becoming {@code 1.285E2}, a
 * {@code byte[]} becoming Base64 without a decision) — the shape here is a contract with consumers,
 * so every branch is explicit.
 */
public class RedisRowSerializerJson implements RedisRowSerializer {

    private final Set<String> excludedColumns;
    private final int maxValueBytes;
    private final long ttlSeconds;

    public RedisRowSerializerJson(Set<String> excludedColumns, int maxValueBytes, long ttlSeconds) {
        this.excludedColumns = RedisValues.lowerCase(excludedColumns);
        this.maxValueBytes = maxValueBytes;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public RedisMutation upsert(String key, Map<String, Object> row) {
        StringBuilder json = new StringBuilder(64 + row.size() * 16).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (excludedColumns.contains(RedisValues.lower(entry.getKey()))) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            RedisValues.appendQuoted(json, entry.getKey());
            json.append(':');
            if (!RedisValues.appendJson(json, entry.getValue())) {
                return null;
            }
        }
        json.append('}');
        if (maxValueBytes > 0 && RedisValues.utf8Length(json) > maxValueBytes) {
            return null;
        }
        return new RedisMutation(RedisMutation.Kind.SET, key, Map.of(), json.toString(),
                List.of(), ttlSeconds);
    }

    @Override
    public RedisMutation delete(String key) {
        return new RedisMutation(RedisMutation.Kind.DEL, key, Map.of(), null, List.of(), 0);
    }
}
