package com.qqmu.jync.service.etl;

import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.etl.mongo.MongoSourceFactory;

/**
 * Chooses the source implementation from the connection preset, not from the task.
 *
 * <p>{@link DatabaseType#MONGODB_NATIVE} has no JDBC driver at all, so it is the only type that
 * bypasses {@link JdbcEtlSource}; every other connection — including custom ones that resolve
 * to a JDBC family — keeps the existing JDBC path.
 */
@Component
public class RoutingEtlSourceFactory implements EtlSourceFactory {

    private final MongoSourceFactory mongo;

    public RoutingEtlSourceFactory(MongoSourceFactory mongo) {
        this.mongo = mongo;
    }

    @Override
    public EtlSource open(DatabaseConfig config, EtlConnectionProvider connections)
            throws SQLException {
        if (config != null && config.effectiveType() == DatabaseType.MONGODB_NATIVE) {
            return mongo.open(config);
        }
        return new JdbcEtlSource(connections.open(config), config);
    }
}
