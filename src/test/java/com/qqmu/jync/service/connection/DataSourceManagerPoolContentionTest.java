package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * Two tasks whose full loads share one connection configuration must not knock each other
 * over: the pool holds five connections, a parallelism-4 load takes all of them at every
 * chunk, and the second task's borrow used to time out after fifteen seconds and fail the
 * whole cycle. Waiting for a seat — and deferring quietly if none frees in time — turns that
 * error into a queue.
 */
class DataSourceManagerPoolContentionTest {

    private final SyncProperties properties = new SyncProperties();
    private DatabaseConfig config;
    private FakeManager manager;

    @BeforeEach
    void setUp() {
        config = new DatabaseConfig();
        config.setId(1L);
        config.setName("orders");
        manager = new FakeManager(properties);
    }

    @Test
    void aPoolWithASeatLeftIsBorrowedStraightAway() throws SQLException {
        manager.saturated.set(false);

        assertThat(manager.getConnectionOrDefer(config)).isSameAs(manager.connection);
        assertThat(manager.borrows).hasValue(1);
    }

    /**
     * With nowhere to park, the doomed borrow is not even attempted: Hikari would block for
     * its own fifteen-second timeout first, and the run is being deferred anyway.
     */
    @Test
    void aPoolThatStaysFullDefersWithoutAttemptingTheBorrow() {
        properties.setPoolWaitMs(0);
        manager.saturated.set(true);

        Throwable thrown = catchThrowable(() -> manager.getConnectionOrDefer(config));

        assertThat(thrown).isInstanceOf(PoolContentionException.class)
                .hasMessageContaining("orders");
        assertThat(manager.borrows).hasValue(0);
    }

    @Test
    void theWaitEndsAsSoonAsTheOtherTaskReleasesItsChunk() throws SQLException {
        properties.setPoolWaitMs(5_000);
        manager.saturated.set(true);
        Thread release = new Thread(() -> {
            sleepQuietly(80);
            manager.saturated.set(false);
        });
        release.start();

        Connection borrowed = manager.getConnectionOrDefer(config);

        joinQuietly(release);
        assertThat(borrowed).isSameAs(manager.connection);
        assertThat(manager.borrows).hasValue(1);
    }

    /**
     * The database being down also ends in Hikari's "Connection is not available" timeout, so
     * the deferral cannot be keyed on the exception alone: only a pool that is still fully
     * checked out is contention. A real connection failure stays a failure.
     */
    @Test
    void aRealConnectionFailureIsNotTurnedIntoADeferral() {
        properties.setPoolWaitMs(0);
        manager.saturated.set(false);
        manager.failure = new SQLTransientConnectionException("Connection refused");

        Throwable thrown = catchThrowable(() -> manager.getConnectionOrDefer(config));

        assertThat(thrown).isInstanceOf(SQLTransientConnectionException.class);
        assertThat(manager.borrows).hasValue(1);
    }

    @Test
    void aBorrowThatLosesTheRaceToAnotherWorkerWhileFullIsADeferral() {
        properties.setPoolWaitMs(0);
        manager.saturated.set(false);
        manager.failure = new SQLTransientConnectionException("Connection is not available");
        // The seat vanished between the check and the borrow: the pool is full again.
        manager.saturateOnBorrow = true;

        assertThatThrownBy(() -> manager.getConnectionOrDefer(config))
                .isInstanceOf(PoolContentionException.class);
    }

    /** Saturation is read from the pool's own counters, not guessed from errors. */
    @Test
    void aPoolIsSaturatedOnlyWhenEveryConnectionIsCheckedOut() {
        HikariDataSource pool = mock(HikariDataSource.class);
        HikariPoolMXBean bean = mock(HikariPoolMXBean.class);
        when(pool.getHikariPoolMXBean()).thenReturn(bean);
        when(pool.getMaximumPoolSize()).thenReturn(5);
        when(bean.getActiveConnections()).thenReturn(4);
        assertThat(DataSourceManager.isSaturated(pool)).isFalse();

        when(bean.getActiveConnections()).thenReturn(5);
        assertThat(DataSourceManager.isSaturated(pool)).isTrue();
    }

    @Test
    void aPoolWithoutCountersOrWithoutIdentityIsNeverReportedAsSaturated() {
        HikariDataSource noBean = mock(HikariDataSource.class);
        when(noBean.getHikariPoolMXBean()).thenReturn(null);

        assertThat(DataSourceManager.isSaturated(noBean)).isFalse();
        assertThat(DataSourceManager.isSaturated(null)).isFalse();
        assertThat(manager.isPoolSaturated(new DatabaseConfig())).isFalse();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The real manager's cache is private; the two decisions under test are overridable. */
    private static final class FakeManager extends DataSourceManager {

        final AtomicBoolean saturated = new AtomicBoolean();
        final AtomicInteger borrows = new AtomicInteger();
        final Connection connection = mock(Connection.class);
        SQLException failure;
        boolean saturateOnBorrow;

        FakeManager(SyncProperties properties) {
            super(null, null, properties);
        }

        @Override
        public boolean isPoolSaturated(DatabaseConfig config) {
            return saturated.get();
        }

        @Override
        public Connection getConnection(DatabaseConfig config) throws SQLException {
            borrows.incrementAndGet();
            if (failure != null) {
                if (saturateOnBorrow) {
                    saturated.set(true);
                }
                throw failure;
            }
            return connection;
        }
    }
}
