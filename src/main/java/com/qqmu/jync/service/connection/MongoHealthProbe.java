package com.qqmu.jync.service.connection;

import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.stereotype.Component;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.etl.mongo.MongoConnectionFactory;

/**
 * The health dot's Mongo branch: one short-lived client whose server selection gives up after
 * three seconds, so an unreachable host cannot hold a page request for the driver's default
 * thirty. A failure is an exception; the caller turns it into an unhealthy row.
 */
@Component
public class MongoHealthProbe {

    static final long SERVER_SELECTION_TIMEOUT_MS = 3_000;

    private final MongoConnectionFactory connections;

    public MongoHealthProbe(MongoConnectionFactory connections) {
        this.connections = connections;
    }

    public void ping(DatabaseConfig config) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connections.uri(config)))
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(
                        SERVER_SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            client.getDatabase(connections.database(config))
                    .runCommand(new Document("ping", 1));
        }
    }
}
