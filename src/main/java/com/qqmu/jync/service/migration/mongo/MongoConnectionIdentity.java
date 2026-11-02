package com.qqmu.jync.service.migration.mongo;

import java.util.HashSet;
import java.util.Set;

import com.mongodb.ConnectionString;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.service.etl.mongo.MongoUriBuilder;
import com.qqmu.jync.service.migration.connection.MongoMigrationClientFactory;

/**
 * The server identity of a MongoDB connection: the normalised host list plus the effective
 * database name. Two connections with the same identity read and write the same data, which a
 * MongoDB → MongoDB run must refuse before its OVERWRITE mode drops anything.
 */
final class MongoConnectionIdentity {

    private MongoConnectionIdentity() {
    }

    static boolean sameDatabase(MigrationConnection source, MigrationConnection target) {
        Identity left = of(source);
        Identity right = of(target);
        return left != null && left.equals(right);
    }

    /** Null when the connection cannot be parsed; the run itself will then report the error. */
    private static Identity of(MigrationConnection connection) {
        try {
            DatabaseConfig config = new DatabaseConfig();
            config.setCustomUrl(connection.getBaseUrl());
            config.setDatabaseName(connection.getDatabaseName());
            config.setUsername(connection.getUsername());
            config.setExtraParams(connection.getExtraParams());
            String uri = MongoUriBuilder.build(config, null);
            String database = MongoMigrationClientFactory.databaseInUri(uri);
            if (database == null) {
                return null;
            }
            return new Identity(database, new HashSet<>(new ConnectionString(uri).getHosts()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Identity(String database, Set<String> hosts) {
    }
}
