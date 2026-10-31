package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * MongoDB's Atlas SQL JDBC driver differs from every relational preset in two ways the URL
 * builder must respect: a database path in the URL is rejected (the database is a JDBC
 * property), and Atlas endpoints are addressed without a port. Getting either wrong fails only
 * at connect time, in someone else's cluster.
 */
class DataSourceManagerMongoTest {

    private final DataSourceManager manager =
            new DataSourceManager(null, null, new SyncProperties());

    private DatabaseConfig mongo(String host, Integer port, String database) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("mongo");
        config.setType(DatabaseType.MONGODB);
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(database);
        return config;
    }

    @Test
    void aBlankPortIsOmittedSoAnAtlasHostIsReachableWithoutOne() {
        assertThat(manager.buildJdbcUrl(mongo("cluster0.abc.mongodb.net", null, "shop")))
                .isEqualTo("jdbc:mongodb://cluster0.abc.mongodb.net");
    }

    @Test
    void anExplicitPortIsKeptForProxiedOrSelfHostedEndpoints() {
        assertThat(manager.buildJdbcUrl(mongo("mongo.internal", 27017, "shop")))
                .isEqualTo("jdbc:mongodb://mongo.internal:27017");
    }

    @Test
    void theDatabaseNameTravelsAsAPropertyNeverInTheUrl() {
        DatabaseConfig config = mongo("cluster0.abc.mongodb.net", null, "  shop  ");

        assertThat(manager.buildJdbcUrl(config)).doesNotContain("shop");
        assertThat(DataSourceManager.driverProperties(config))
                .containsExactly(Map.entry("database", "shop"));
    }

    @Test
    void relationalPresetsGetNoExtraDriverProperties() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        config.setDatabaseName("shop");

        assertThat(DataSourceManager.driverProperties(config)).isEmpty();
    }

    @Test
    void aMongoConnectionWithNoDatabaseDoesNotInventOne() {
        assertThat(DataSourceManager.driverProperties(mongo("h", null, "   "))).isEmpty();
    }
}
