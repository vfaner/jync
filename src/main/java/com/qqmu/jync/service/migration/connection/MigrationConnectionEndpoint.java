package com.qqmu.jync.service.migration.connection;

/**
 * The normalised endpoint a connection form resolves to, before it is written onto the entity.
 *
 * <p>Search adapters only fill {@code baseUrl}; MongoDB fills a database name; KingbaseES fills
 * a database, a schema and extra JDBC parameters.
 */
public record MigrationConnectionEndpoint(String baseUrl, String databaseName,
                                          String schemaName, String extraParams) {
}
