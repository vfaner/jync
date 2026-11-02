package com.qqmu.jync.service.migration.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.service.etl.mongo.MongoUriBuilder;
import com.qqmu.jync.service.migration.MigrationGlob;

/** Native MongoDB access for dedicated migration, through the bundled synchronous driver. */
@Component
public class MongoConnectionAdapter implements MigrationConnectionKindAdapter {

    private final MongoMigrationClientFactory clientFactory;

    public MongoConnectionAdapter(MongoMigrationClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public Set<MigrationConnectionKind> kinds() {
        return Set.of(MigrationConnectionKind.MONGODB);
    }

    @Override
    public MigrationConnectionEndpoint normalize(MigrationConnectionForm form) {
        MongoUriBuilder.preview(toConfig(form));

        String uriDatabase = MongoMigrationClientFactory.databaseInUri(form.getBaseUrl());
        String fieldDatabase = trimToNull(form.getDatabaseName());
        if (uriDatabase != null && fieldDatabase != null
                && !uriDatabase.equals(fieldDatabase)) {
            throw new IllegalArgumentException(
                    "error.migration.connection.mongo.database.mismatch");
        }
        return new MigrationConnectionEndpoint(form.getBaseUrl().trim(), fieldDatabase, null,
                trimToNull(form.getExtraParams()));
    }

    @Override
    public void validateAuth(MigrationConnectionForm form, String rawPassword, String rawApiKey,
                             MigrationConnection existing) {
        MigrationConnectionAuthType authType = form.getAuthType();
        if (authType == MigrationConnectionAuthType.API_KEY) {
            throw new IllegalArgumentException(
                    "error.migration.connection.mongo.auth.unsupported");
        }
        if (authType != MigrationConnectionAuthType.BASIC) {
            return;
        }
        if (form.getUsername() == null || form.getUsername().isBlank()) {
            throw new IllegalArgumentException("error.migration.connection.username.required");
        }
        boolean hasRaw = rawPassword != null && !rawPassword.isEmpty();
        boolean hasStored = existing != null && existing.getPasswordEnc() != null;
        if (!hasRaw && !hasStored) {
            throw new IllegalArgumentException("error.migration.connection.password.required");
        }
    }

    @Override
    public MigrationConnectionTestResult test(MigrationConnection connection) {
        long start = System.nanoTime();
        try (MongoClient client = clientFactory.open(connection)) {
            return probe(client, connection, start);
        } catch (RuntimeException e) {
            return failure(e, start);
        }
    }

    @Override
    public MigrationConnectionTestResult testTransient(MigrationConnection connection,
                                                       String plainPassword, String plainApiKey) {
        long start = System.nanoTime();
        try (MongoClient client = clientFactory.open(connection, plainPassword)) {
            return probe(client, connection, start);
        } catch (RuntimeException e) {
            return failure(e, start);
        }
    }

    private MigrationConnectionTestResult probe(MongoClient client,
                                                MigrationConnection connection, long start) {
        MongoDatabase database = client.getDatabase(clientFactory.database(connection));
        database.runCommand(new Document("ping", 1));
        Document buildInfo = database.runCommand(new Document("buildInfo", 1));
        return new MigrationConnectionTestResult(true, null, "MongoDB",
                buildInfo.getString("version"), database.getName(), elapsedMs(start));
    }

    private static MigrationConnectionTestResult failure(RuntimeException e, long start) {
        return new MigrationConnectionTestResult(false, e.getMessage(), null, null, null,
                elapsedMs(start));
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public List<MigrationDiscoveryItem> list(MigrationConnection connection, String pattern) {
        String effective = pattern == null ? "" : pattern.trim();
        boolean systemAsked = MigrationGlob.namesPrefix(effective, "system.");
        try (MongoClient client = clientFactory.open(connection)) {
            MongoDatabase database = client.getDatabase(clientFactory.database(connection));
            List<String> names = database.listCollectionNames().into(new ArrayList<>());
            List<MigrationDiscoveryItem> items = new ArrayList<>();
            for (String name : names) {
                if (name.startsWith("system.") && !systemAsked) {
                    continue;
                }
                if (!MigrationGlob.matchesAny(name, effective)) {
                    continue;
                }
                items.add(new MigrationDiscoveryItem(name,
                        database.getCollection(name).estimatedDocumentCount(),
                        "", "", ""));
            }
            return items;
        }
    }

    private static DatabaseConfig toConfig(MigrationConnectionForm form) {
        DatabaseConfig config = new DatabaseConfig();
        config.setCustomUrl(form.getBaseUrl());
        config.setDatabaseName(form.getDatabaseName());
        config.setUsername(form.getUsername());
        config.setExtraParams(form.getExtraParams());
        return config;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
