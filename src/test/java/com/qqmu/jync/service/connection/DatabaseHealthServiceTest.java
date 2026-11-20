package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.LogCapture;
import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The connections page shows a health dot per row. Probing a production database is the riskiest
 * thing that page does, so the rules are pinned here: one short connection per check, fast
 * failures, and a cache so opening the page twice does not open the databases twice.
 */
class DatabaseHealthServiceTest {

    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);

    private final DatabaseHealthService service = new DatabaseHealthService(
            dataSourceManager, mock(MongoHealthProbe.class),
            Duration.ofSeconds(3), Duration.ofSeconds(60));

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
        // The page swaps this exact key for a label; driver text is passed through instead.
        assertThat(health.get(0).message()).isEqualTo(DatabaseHealthService.INVALID_MESSAGE);
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
                dataSourceManager, mock(MongoHealthProbe.class),
                Duration.ofMillis(150), Duration.ofSeconds(60));

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

    /**
     * Hikari's shutdown can outlive the page's budget: it waits for its own connection executor
     * to finish. The budget must abandon that wait without interrupting the worker — an
     * interrupt <em>inside</em> {@code close()} aborts Hikari's shutdown at the first
     * {@code awaitTermination}, skips closing the pool's connections, and logs
     * "Interrupted during closing" with a stack that reads like a failure.
     */
    @Test
    void aProbeStillClosingWhenTheBudgetExpiresIsNotInterruptedMidShutdown() throws Exception {
        DatabaseConfig config = config(11L, "slow-close");
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(pool.getConnection()).thenReturn(connection);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean interruptedWhileClosing = new AtomicBoolean();
        doAnswer(invocation -> {
            closing.countDown();
            // Stand in for Hikari's awaitTermination: still running when the budget expires.
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
            while (System.nanoTime() < until) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                if (Thread.currentThread().isInterrupted()) {
                    interruptedWhileClosing.set(true);
                    Thread.interrupted();
                }
            }
            closed.countDown();
            return null;
        }).when(pool).close();
        when(dataSourceManager.createProbeDataSource(config)).thenReturn(pool);
        DatabaseHealthService impatient = new DatabaseHealthService(
                dataSourceManager, mock(MongoHealthProbe.class),
                Duration.ofMillis(150), Duration.ofSeconds(60));

        long start = System.currentTimeMillis();
        List<DatabaseHealthService.Health> health = impatient.check(List.of(config), true);
        long took = System.currentTimeMillis() - start;

        assertThat(took).as("the page returns on budget, the close keeps running").isLessThan(1_000);
        assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interruptedWhileClosing)
                .as("the budget abandons the probe without interrupting its Hikari shutdown")
                .isFalse();
        assertThat(health.get(0).message()).isEqualTo(DatabaseHealthService.TIMEOUT_MESSAGE);
    }

    /**
     * The same case against a real Hikari pool: with the flag set, HikariCP logs
     * "Interrupted during closing" with a full stack and stops before closing the connections.
     */
    @Test
    void aProbePoolFinishesClosingEvenWhenTheThreadIsInterrupted() throws SQLException {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("probe-close-test");
        pool.setJdbcUrl("jdbc:h2:mem:probe-close-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        try (Connection connection = pool.getConnection()) {
            // start the pool and its executors
        }
        ListAppender<ILoggingEvent> logs = LogCapture.attach(HikariDataSource.class);
        Thread.currentThread().interrupt();
        try {
            DatabaseHealthService.closeProbePool(pool);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller's interrupt status is restored")
                    .isTrue();
        } finally {
            Thread.interrupted();
            LogCapture.detach(HikariDataSource.class, logs);
        }

        assertThat(pool.isClosed()).isTrue();
        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .noneMatch(message -> message.contains("Interrupted during closing"));
        assertThat(LogCapture.messagesAt(Level.INFO, logs))
                .anyMatch(message -> message.contains("Shutdown completed"));
    }
}
