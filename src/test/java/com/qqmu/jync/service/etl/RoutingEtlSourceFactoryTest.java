package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.etl.mongo.MongoSourceFactory;

/**
 * The preset, not the caller, decides which driver reads the source. Every other type keeps
 * the JDBC path, including custom connections that resolve to a JDBC family.
 */
class RoutingEtlSourceFactoryTest {

    private final MongoSourceFactory mongo = mock(MongoSourceFactory.class);
    private final RoutingEtlSourceFactory routing = new RoutingEtlSourceFactory(mongo);

    @Test
    void theNativePresetOpensTheMongoSource() throws SQLException {
        DatabaseConfig config = config(DatabaseType.MONGODB_NATIVE);
        EtlSource mongoSource = mock(EtlSource.class);
        when(mongo.open(config)).thenReturn(mongoSource);
        EtlConnectionProvider connections = mock(EtlConnectionProvider.class);

        assertThat(routing.open(config, connections)).isSameAs(mongoSource);
        verify(connections, never()).open(config);
    }

    @Test
    void everyOtherTypeOpensAJdbcSource() throws SQLException {
        DatabaseConfig config = config(DatabaseType.H2);
        EtlConnectionProvider connections = mock(EtlConnectionProvider.class);
        when(connections.open(config)).thenReturn(mock(Connection.class));

        assertThat(routing.open(config, connections)).isInstanceOf(JdbcEtlSource.class);
        verify(connections).open(config);
    }

    private static DatabaseConfig config(DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setType(type);
        return config;
    }
}
