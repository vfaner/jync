package com.qqmu.jync.dto;

import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;

import lombok.Getter;
import lombok.Setter;

/**
 * How one project projects rows into its Redis target.
 *
 * <p>These are per-project, not per-connection: one Redis target can serve several projects,
 * and each must claim a different key prefix. They live in {@link SyncConfig} (serialized JSON)
 * so no column, entity or migration is added for a cache projection.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisProjectionConfig {

    /** Prefix of every key this project writes; a trailing ':' is always present. */
    private String keyPrefix;

    /** JSON (one string per row) or HASH (one field per column). */
    private RedisValueFormat valueFormat = RedisValueFormat.JSON;

    /** Key expiry in seconds; 0 means the key never expires. */
    private long ttlSeconds;

    /** Columns never written to Redis, matched case-insensitively. */
    private Set<String> excludedColumns = new LinkedHashSet<>();

    /** Per-row value cap in bytes; a row over it is skipped rather than truncated. */
    private int maxValueBytes = 1_048_576;

    /** Pipelined commands buffered per CDC transaction before a flush is forced. */
    private int maxCommandsPerTransaction = 10_000;

    /**
     * The contract a project gets before its first save: {@code jync:<projectId>:}.
     *
     * <p>The project id makes the prefix unique on a shared Redis without the user having to
     * invent one, and the trailing ':' keeps the namespace boundary visible in every key.
     */
    public static RedisProjectionConfig defaultFor(Long projectId) {
        RedisProjectionConfig config = new RedisProjectionConfig();
        config.setKeyPrefix("jync:" + projectId + ":");
        return config;
    }
}
