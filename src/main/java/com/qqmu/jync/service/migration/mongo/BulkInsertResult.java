package com.qqmu.jync.service.migration.mongo;

/**
 * What one {@code insertMany} did. The driver reports partial success through an exception, so
 * duplicates are counted here instead of failing the whole batch: {@code APPEND} tolerates them
 * and the other write modes treat them as an error.
 */
public record BulkInsertResult(int inserted, int duplicates) {
}
