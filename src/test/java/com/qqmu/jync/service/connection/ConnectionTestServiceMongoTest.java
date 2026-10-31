package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.BinlogCdcProbe;
import com.qqmu.jync.service.cdc.LogMinerCdcProbe;
import com.qqmu.jync.service.cdc.PgLogicalReplicationCdcProbe;
import com.qqmu.jync.service.cdc.SqlServerCdcProbe;
import com.qqmu.jync.service.cdc.YStreamCdcProbe;
import com.qqmu.jync.service.etl.mongo.MongoConnectionFactory;

/**
 * A native Mongo connection has no JDBC driver, so the test button must not ask for a jar and
 * must not touch Hikari. It dials with the bundled synchronous driver and reports the same
 * {@link ConnectionTestService.TestResult} shape as every other preset.
 */
class ConnectionTestServiceMongoTest {

    private final MongoConnectionTester mongoTester = mock(MongoConnectionTester.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);

    @Test
    void aNativeConfigIsTestedByTheMongoTester() throws Exception {
        DatabaseConfig config = nativeConfig();
        ConnectionTestService.TestResult expected = ConnectionTestService.TestResult.success(
                "MongoDB 7.0.5", "MongoDB Java Driver", "shop", null, 5);
        when(mongoTester.test(config)).thenReturn(expected);

        assertThat(service().test(config)).isSameAs(expected);
        verify(dataSourceManager, never()).getConnection(any());
        verify(dataSourceManager, never()).createTransientDataSource(any());
    }

    @Test
    void aNativeConfigDoesNotNeedAnExternalJar() {
        DatabaseConfig config = nativeConfig();
        when(mongoTester.test(config)).thenReturn(ConnectionTestService.TestResult.failure("down", 3));

        ConnectionTestService.TestResult result = service().test(config);

        assertThat(result.getMessage()).isNotEqualTo("error.driver.jar.required");
        verify(mongoTester).test(config);
    }

    @Test
    void aMongoFailureKeepsTheDriverMessage() {
        DatabaseConfig config = nativeConfig();
        when(mongoTester.test(config))
                .thenReturn(ConnectionTestService.TestResult.failure("connection refused", 12));

        ConnectionTestService.TestResult result = service().test(config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("connection refused");
    }

    @Test
    void theTesterReportsTheServerVersionAndClosesTheClient() {
        DatabaseConfig config = nativeConfig();
        MongoConnectionFactory factory = mock(MongoConnectionFactory.class);
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(factory.create(config)).thenReturn(client);
        when(factory.database(config)).thenReturn("shop");
        when(client.getDatabase("shop")).thenReturn(database);
        when(database.runCommand(any(Document.class)))
                .thenReturn(new Document("version", "7.0.5"));

        ConnectionTestService.TestResult result = new MongoConnectionTester(factory).test(config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProductInfo()).isEqualTo("MongoDB 7.0.5");
        assertThat(result.getCatalog()).isEqualTo("shop");
        verify(client).close();
    }

    @Test
    void theTesterKeepsTheDriverMessageOnFailure() {
        DatabaseConfig config = nativeConfig();
        MongoConnectionFactory factory = mock(MongoConnectionFactory.class);
        when(factory.create(config)).thenThrow(new IllegalStateException("connection refused"));

        ConnectionTestService.TestResult result = new MongoConnectionTester(factory).test(config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("connection refused");
    }

    // --- helpers ------------------------------------------------------------------------

    private ConnectionTestService service() {
        return new ConnectionTestService(dataSourceManager, mongoTester,
                mock(BinlogCdcProbe.class),
                mock(LogMinerCdcProbe.class),
                mock(PgLogicalReplicationCdcProbe.class),
                mock(SqlServerCdcProbe.class),
                mock(YStreamCdcProbe.class),
                new TargetDdlProbe(),
                new SyncProperties());
    }

    private static DatabaseConfig nativeConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setName("mongo");
        config.setType(DatabaseType.MONGODB_NATIVE);
        config.setHost("127.0.0.1");
        config.setPort(27017);
        config.setDatabaseName("shop");
        return config;
    }
}
