package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.etl.mongo.MongoConnectionFactory;

/**
 * The health dot for a native Mongo connection must not go through Hikari: Hikari asks for a
 * JDBC URL that this preset does not have. It pings with the bundled driver instead, under the
 * same page budget as every other row.
 */
class DatabaseHealthServiceMongoTest {

    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final MongoHealthProbe mongoProbe = mock(MongoHealthProbe.class);

    @Test
    void aNativeConnectionIsProbedWithoutHikari() {
        DatabaseConfig config = nativeConfig();
        DatabaseHealthService service = service(mongoProbe, Duration.ofSeconds(3));

        List<DatabaseHealthService.Health> health = service.check(List.of(config), true);

        assertThat(health).hasSize(1);
        assertThat(health.get(0).ok()).isTrue();
        verify(dataSourceManager, never()).createProbeDataSource(any());
        verify(mongoProbe).ping(config);
    }

    @Test
    void aMongoFailureMakesTheRowUnhealthyWithTheDriverMessage() {
        DatabaseConfig config = nativeConfig();
        doThrow(new IllegalStateException("connection refused")).when(mongoProbe).ping(config);
        DatabaseHealthService service = service(mongoProbe, Duration.ofSeconds(3));

        List<DatabaseHealthService.Health> health = service.check(List.of(config), true);

        assertThat(health.get(0).ok()).isFalse();
        assertThat(health.get(0).message()).isEqualTo("connection refused");
    }

    @Test
    void aSlowMongoProbeIsCutOffByThePageBudget() {
        MongoHealthProbe slow = new MongoHealthProbe(mock(MongoConnectionFactory.class)) {
            @Override
            public void ping(DatabaseConfig config) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        DatabaseHealthService service = service(slow, Duration.ofMillis(100));

        long start = System.currentTimeMillis();
        List<DatabaseHealthService.Health> health = service.check(List.of(nativeConfig()), true);

        assertThat(health.get(0).ok()).isFalse();
        assertThat(health.get(0).message()).isEqualTo(DatabaseHealthService.TIMEOUT_MESSAGE);
        assertThat(System.currentTimeMillis() - start).isLessThan(2_000);
    }

    // --- helpers ------------------------------------------------------------------------

    private DatabaseHealthService service(MongoHealthProbe probe, Duration budget) {
        return new DatabaseHealthService(dataSourceManager, probe, budget, Duration.ofSeconds(60));
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
