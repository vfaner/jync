package com.qqmu.jync.model;

/**
 * What a project writes to.
 *
 * <p>Separate from {@link DatabaseType} on purpose: every JDBC/SQL path (dialects, metadata
 * readers, ETL, the five CDC consumers) assumes a relational target, and a Redis target does
 * not speak any of it. Keeping the kind on the project leaves those paths untouched.
 */
public enum SyncTargetKind {
    /** A relational target described by {@code DatabaseConfig}; the historical behaviour. */
    JDBC,
    /** A cache target described by {@link RedisTarget}. */
    REDIS
}
