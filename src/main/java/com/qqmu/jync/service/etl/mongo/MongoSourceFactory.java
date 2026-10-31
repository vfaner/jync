package com.qqmu.jync.service.etl.mongo;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.etl.EtlSource;
import com.qqmu.jync.service.etl.MongoEtlSource;

/**
 * Opens a native-driver source for one run: one client, one database, one gateway.
 *
 * <p>The client is created lazily by the driver, so an unreachable server is not an error
 * here; it surfaces on the first read and is attributed to the source side there.
 */
@Component
public class MongoSourceFactory {

    private final MongoConnectionFactory connections;

    public MongoSourceFactory(MongoConnectionFactory connections) {
        this.connections = connections;
    }

    public EtlSource open(DatabaseConfig config) {
        return new MongoEtlSource(new DriverMongoSourceGateway(
                connections.create(config), connections.database(config)), config);
    }
}
