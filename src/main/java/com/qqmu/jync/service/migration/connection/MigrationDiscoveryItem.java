package com.qqmu.jync.service.migration.connection;

/**
 * One copyable object discovered on a source connection: an index for the search engines, a
 * collection for MongoDB, or a table for KingbaseES. Discovery never touches the target.
 */
public record MigrationDiscoveryItem(String name, long docsCount, String storeSize,
                                     String status, String health) {
}
