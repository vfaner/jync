package com.qqmu.jync.service.connection;

import org.bson.Document;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.etl.mongo.MongoConnectionFactory;

/**
 * The test button for a native Mongo connection. It dials with the bundled synchronous driver,
 * reads the server version and closes the client; it never asks for a JDBC driver or a jar.
 */
@Component
public class MongoConnectionTester {

    private final MongoConnectionFactory connections;

    public MongoConnectionTester(MongoConnectionFactory connections) {
        this.connections = connections;
    }

    public ConnectionTestService.TestResult test(DatabaseConfig config) {
        long start = System.currentTimeMillis();
        try (MongoClient client = connections.create(config)) {
            String database = connections.database(config);
            MongoDatabase db = client.getDatabase(database);
            db.runCommand(new Document("ping", 1));
            String version = db.runCommand(new Document("buildInfo", 1)).getString("version");
            return ConnectionTestService.TestResult.success(
                    "MongoDB " + (version == null ? "" : version),
                    "MongoDB Java Driver", database, null,
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ConnectionTestService.TestResult.failure(
                    e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }
}
