package com.qqmu.jync.service.migration.connection;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.service.etl.mongo.MongoUriBuilder;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Builds a native driver client for a dedicated MongoDB connection.
 *
 * <p>The password lives only in the encrypted column: it is decrypted here and rendered into
 * the URI handed to the driver. {@code baseUrl} never contains it — {@link MongoUriBuilder}
 * refuses a password inside a pasted URI.
 */
@Component
public class MongoMigrationClientFactory {

    private final CryptoUtil cryptoUtil;

    public MongoMigrationClientFactory(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    /** The real URI for a saved connection, with its decrypted password. */
    public String uri(MigrationConnection connection) {
        return uri(connection, cryptoUtil.decrypt(connection.getPasswordEnc()));
    }

    public String uri(MigrationConnection connection, String plainPassword) {
        return MongoUriBuilder.build(toConfig(connection), plainPassword);
    }

    /** The database this connection points at: the URI path, else the explicit field. */
    public String database(MigrationConnection connection) {
        String database = databaseInUri(connection.getBaseUrl());
        if (database == null) {
            database = connection.getDatabaseName() == null
                    ? null : connection.getDatabaseName().trim();
        }
        if (database == null || database.isEmpty()) {
            throw new IllegalArgumentException("error.mongodb.native.database.required");
        }
        return database;
    }

    /** The database in the URI path, if any; {@code mongodb+srv} has no implicit path. */
    public static String databaseInUri(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        int slash = value.indexOf('/', scheme + 3);
        if (slash < 0) {
            return null;
        }
        String database = value.substring(slash + 1).trim();
        return database.isEmpty() ? null : database;
    }

    public MongoClientSettings settings(MigrationConnection connection, String plainPassword) {
        int connectTimeout = connection.getConnectTimeoutMs();
        int requestTimeout = connection.getRequestTimeoutMs();
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri(connection, plainPassword)))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(requestTimeout, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(builder -> builder
                        .serverSelectionTimeout(connectTimeout, TimeUnit.MILLISECONDS))
                .build();
    }

    public MongoClient open(MigrationConnection connection) {
        return open(connection, cryptoUtil.decrypt(connection.getPasswordEnc()));
    }

    public MongoClient open(MigrationConnection connection, String plainPassword) {
        return MongoClients.create(settings(connection, plainPassword));
    }

    private static DatabaseConfig toConfig(MigrationConnection connection) {
        DatabaseConfig config = new DatabaseConfig();
        config.setCustomUrl(connection.getBaseUrl());
        config.setDatabaseName(connection.getDatabaseName());
        config.setUsername(connection.getUsername());
        config.setExtraParams(connection.getExtraParams());
        return config;
    }
}
