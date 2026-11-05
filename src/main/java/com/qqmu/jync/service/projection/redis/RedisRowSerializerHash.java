package com.qqmu.jync.service.projection.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The optional projection: one hash per row, field per column, every value a string.
 *
 * <p>A column that is SQL {@code NULL} produces no field, so a row previously carrying a value
 * needs an {@code HDEL} for it — otherwise a cache reader keeps seeing the stale value. Those
 * columns travel in {@link RedisMutation#deleteFields()}.
 */
public class RedisRowSerializerHash implements RedisRowSerializer {

    private final Set<String> excludedColumns;
    private final int maxValueBytes;
    private final long ttlSeconds;

    public RedisRowSerializerHash(Set<String> excludedColumns, int maxValueBytes, long ttlSeconds) {
        this.excludedColumns = RedisValues.lowerCase(excludedColumns);
        this.maxValueBytes = maxValueBytes;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public RedisMutation upsert(String key, Map<String, Object> row) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> deleteFields = new ArrayList<>();
        int bytes = 0;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (excludedColumns.contains(RedisValues.lower(entry.getKey()))) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                deleteFields.add(entry.getKey());
                continue;
            }
            String text = RedisValues.text(value);
            if (text == null) {
                return null;
            }
            fields.put(entry.getKey(), text);
            bytes += RedisValues.utf8Length(entry.getKey()) + RedisValues.utf8Length(text);
        }
        if (maxValueBytes > 0 && bytes > maxValueBytes) {
            return null;
        }
        return new RedisMutation(RedisMutation.Kind.HSET, key, fields, null, deleteFields,
                ttlSeconds);
    }

    @Override
    public RedisMutation delete(String key) {
        return new RedisMutation(RedisMutation.Kind.DEL, key, Map.of(), null, List.of(), 0);
    }
}
