package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The connections page shows a health dot per row. Probing a production database is the riskiest
 * thing that page does, so the rules are pinned here: one short connection per check, fast
 * failures, and a cache so opening the page twice does not open the databases twice.
 */
class DatabaseHealthServiceTest {

    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);

    private final DatabaseHealthService service = new DatabaseHealthService(
            dataSourceManager, Duration.ofSeconds(3), Duration.ofSeconds(60));

    private DatabaseConfig config(long id, String name) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setRole(ConnectionRole.SOURCE);
        config.setType(DatabaseType.MYSQL);
        config.setHost("127.0.0.1");
        config.setPort(3306);
        config.setDatabaseName("jyncdb");
        config.setUsername("root");
        config.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        config.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return config;
    }

    private HikariDataSource healthyPool() throws SQLException {
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        return pool;
    }

    @Test
    void aReachableDatabaseIsReportedHealthyWithItsElapsedTime() throws SQLException {
        DatabaseConfig config = config(1L, "MySQL源");
        HikariDataSource pool = healthyPool();
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);

        List<DatabaseHealthService.Health> health = service.check(List.of(config), false);

        assertThat(health).hasSize(1);
        assertThat(health.get(0).id()).isEqualTo(1L);
        assertThat(health.get(0).ok()).isTrue();
        assertThat(health.get(0).elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(health.get(0).message()).isNull();
    }

    @Test
    void aDriverErrorIsReportedAsUnhealthyWithItsOwnMessage() throws SQLException {
        DatabaseConfig config = config(2L, "Oracle源");
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenThrow(new SQLException("Access denied", "28000"));
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);

        List<DatabaseHealthService.Health> health = service.check(List.of(config), false);

        assertThat(health.get(0).ok()).isFalse();
        assertThat(health.get(0).message()).contains("Access denied");
    }

    @Test
    void aConnectionThatConnectsButFailsValidationIsUnhealthy() throws SQLException {
        DatabaseConfig config = config(3L, "PG源");
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);

        List<DatabaseHealthService.Health> health = service.check(List.of(config), false);

        assertThat(health.get(0).ok()).isFalse();
    }

    @Test
    void oneBrokenConnectionDoesNotStopTheOthers() throws SQLException {
        DatabaseConfig good = config(4L, "good");
        DatabaseConfig bad = config(5L, "bad");
        HikariDataSource goodPool = healthyPool();
        when(dataSourceManager.createProbeDataSource(good)).thenReturn(goodPool);
        HikariDataSource broken = mock(HikariDataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("Connection refused", "08S01"));
        when(dataSourceManager.createProbeDataSource(bad)).thenReturn(broken);

        List<DatabaseHealthService.Health> health = service.check(List.of(bad, good), false);

        assertThat(health).extracting(DatabaseHealthService.Health::id).containsExactly(5L, 4L);
        assertThat(health.get(0).ok()).isFalse();
        assertThat(health.get(1).ok()).isTrue();
    }

    @Test
    void aSecondLookWithinTheCacheWindowDoesNotTouchTheDatabaseAgain() throws SQLException {
        DatabaseConfig config = config(6L, "MySQL源");
        HikariDataSource pool = healthyPool();
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);

        service.check(List.of(config), false);
        service.check(List.of(config), false);

        verify(dataSourceManager, times(1)).createProbeDataSource(config);
    }

    @Test
    void forcingARefreshProbesAgainEvenInsideTheCacheWindow() throws SQLException {
        DatabaseConfig config = config(7L, "MySQL源");
        HikariDataSource pool = healthyPool();
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);

        service.check(List.of(config), false);
        service.check(List.of(config), true);

        verify(dataSourceManager, times(2)).createProbeDataSource(config);
    }

    @Test
    void anEditedConnectionIsNotServedFromTheOldCacheEntry() throws SQLException {
        DatabaseConfig config = config(8L, "MySQL源");
        HikariDataSource pool = healthyPool();
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);
        service.check(List.of(config), false);

        config.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        HikariDataSource afterEdit = healthyPool();
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(afterEdit);
        service.check(List.of(config), false);

        verify(dataSourceManager, times(2)).createProbeDataSource(config);
    }

    @Test
    void aProbeThatOutlivesTheBudgetIsReportedAsATimeoutInsteadOfHangingThePage() throws SQLException {
        DatabaseConfig config = config(9L, "slow");
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return mock(Connection.class);
        });
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);
        DatabaseHealthService impatient = new DatabaseHealthService(
                dataSourceManager, Duration.ofMillis(150), Duration.ofSeconds(60));

        long start = System.currentTimeMillis();
        List<DatabaseHealthService.Health> health = impatient.check(List.of(config), true);
        long took = System.currentTimeMillis() - start;

        assertThat(health.get(0).ok()).isFalse();
        assertThat(health.get(0).message()).isEqualTo("error.db.health.timeout");
        assertThat(took).isLessThan(1_500);
    }

    @Test
    void aFailureIsCachedTooSoAnUnreachableHostIsNotDialledOnEveryLook() throws SQLException {
        DatabaseConfig config = config(10L, "down");
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenThrow(new SQLException("Connection refused", "08S01"));
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);

        service.check(List.of(config), false);
        List<DatabaseHealthService.Health> second = service.check(List.of(config), false);

        verify(dataSourceManager, times(1)).createProbeDataSource(config);
        assertThat(second.get(0).ok()).isFalse();
    }
}
