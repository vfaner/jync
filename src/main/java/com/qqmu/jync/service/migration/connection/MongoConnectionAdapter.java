package com.qqmu.jync.service.migration.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.annotation.PreDestroy;

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

    /**
     * Discovery used to call {@code estimatedDocumentCount()} once per collection in
     * sequence: a database with a few thousand collections paid a few thousand round trips
     * before the page rendered. The count is a metadata read and the driver client is
     * thread-safe, so a small fixed pool turns that into a handful of batches. Bounded on
     * purpose — this must not become a load generator against a production server.
     */
    private final ExecutorService countPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "mongo-discovery-count");
        t.setDaemon(true);
        return t;
    });

    public MongoConnectionAdapter(MongoMigrationClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @PreDestroy
    void shutdown() {
        countPool.shutdownNow();
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
            List<String> matched = new ArrayList<>();
            for (String name : names) {
                if (name.startsWith("system.") && !systemAsked) {
                    continue;
                }
                if (MigrationGlob.matchesAny(name, effective)) {
                    matched.add(name);
                }
            }
            // Counts in parallel, results kept in the names' order so the page is stable.
            List<Future<Long>> counts = new ArrayList<>(matched.size());
            for (String name : matched) {
                counts.add(countPool.submit(
                        () -> database.getCollection(name).estimatedDocumentCount()));
            }
            List<MigrationDiscoveryItem> items = new ArrayList<>(matched.size());
            for (int i = 0; i < matched.size(); i++) {
                items.add(new MigrationDiscoveryItem(matched.get(i), awaitCount(counts.get(i)),
                        "", "", ""));
            }
            return items;
        }
    }

    /** The count from one pool task, rethrowing the driver's own failure unchanged. */
    private static long awaitCount(Future<Long> count) {
        try {
            return count.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MongoDB discovery was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
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
