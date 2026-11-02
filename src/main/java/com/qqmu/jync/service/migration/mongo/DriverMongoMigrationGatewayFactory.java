package com.qqmu.jync.service.migration.mongo;

import org.springframework.stereotype.Component;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.service.migration.connection.MongoMigrationClientFactory;

@Component
public class DriverMongoMigrationGatewayFactory implements MongoMigrationGatewayFactory {

    private final MongoMigrationClientFactory clientFactory;

    public DriverMongoMigrationGatewayFactory(MongoMigrationClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public MongoMigrationGateway open(MigrationConnection connection) {
        MongoClient client = clientFactory.open(connection);
        try {
            MongoDatabase database = client.getDatabase(clientFactory.database(connection));
            return new DriverMongoMigrationGateway(client, database);
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }
}
