package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * The native MongoDB connection is served by the Mongo client, never by Hikari/JDBC. Any
 * JDBC-shaped entry point that receives it must fail with an i18n key instead of an NPE on
 * the missing driver class or a URL template that is not a JDBC URL.
 */
class DataSourceManagerNativeMongoTest {

    private static DatabaseConfig config() {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("mongo-native");
        c.setType(DatabaseType.MONGODB_NATIVE);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("localhost");
        c.setDatabaseName("shop");
        return c;
    }

    private static DataSourceManager manager() {
        return new DataSourceManager(null, null, new SyncProperties());
    }

    @Test
    void theJdbcUrlBuilderRefusesANativeMongoConnection() {
        assertThatThrownBy(() -> manager().buildJdbcUrl(config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
    }

    @Test
    void theDriverClassResolverRefusesANativeMongoConnection() {
        assertThatThrownBy(() -> manager().resolveDriverClass(config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
    }

    @Test
    void aTransientPoolIsNeverCreatedForANativeMongoConnection() {
        assertThatThrownBy(() -> manager().createTransientDataSource(config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
    }

    @Test
    void aProbePoolIsNeverCreatedForANativeMongoConnection() {
        assertThatThrownBy(() -> manager().createProbeDataSource(config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
    }

    @Test
    void driverPropertiesAreNeverComputedForANativeMongoConnection() {
        assertThatThrownBy(() -> DataSourceManager.driverProperties(config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
    }
}
