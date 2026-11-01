package com.qqmu.jync.service.metrics.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * SQL Server keeps sessions and locks in DMVs and the buffer-cache hit ratio among the
 * performance counters. Nothing in the DMVs is a plain cumulative statement count, so the
 * counter is the sum of cached plans' {@code execution_count} — it can fall back when a
 * plan is evicted, and the sampler already treats a falling counter as "unknown".
 */
class SqlServerRuntimeMetricsReaderTest {

    private final SqlServerRuntimeMetricsReader reader = new SqlServerRuntimeMetricsReader();

    @Test
    void onlyTheSqlServerFamilyIsServed() {
        for (DatabaseType type : DatabaseType.values()) {
            if (type.getFamily() == DatabaseType.DialectFamily.SQLSERVER) {
                assertThat(reader.supports(type)).as(type.name()).isTrue();
            }
        }
        assertThat(reader.supports(DatabaseType.MYSQL)).isFalse();
        assertThat(reader.supports(DatabaseType.ORACLE)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    void theDmvRowsBecomeSessionsCounterHitRateAndLocks() throws SQLException {
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"23"}),
                StubResultSet.of(new String[] {"900000"}),
                StubResultSet.of(new String[] {"97.5"}),
                StubResultSet.of(new String[] {"2"})));

        assertThat(reading.sessions()).isEqualTo(23);
        assertThat(reading.counter()).isEqualTo(900000L);
        assertThat(reading.cacheHitPercent()).isEqualTo(97.5);
        assertThat(reading.lockWaits()).isEqualTo(2);
    }

    @Test
    void aDeniedLockDmvStillLeavesTheOtherFigures() throws SQLException {
        Connection connection = StubConnection.returning(
                StubConnection.statement(StubResultSet.of(new String[] {"23"})),
                StubConnection.statement(StubResultSet.of(new String[] {"900000"})),
                StubConnection.statement(StubResultSet.of(new String[] {"97.5"})),
                StubConnection.denied(
                        new SQLException("The user does not have permission to perform this action")));

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isEqualTo(23);
        assertThat(reading.counter()).isEqualTo(900000L);
        assertThat(reading.lockWaits()).isNull();
    }

    @Test
    void anEmptyPlanCacheLeavesTheCounterUnknown() throws SQLException {
        // SUM() over no rows comes back as NULL, not 0.
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"23"}),
                StubResultSet.of(new String[] {null}),
                StubResultSet.of(new String[] {"97.5"}),
                StubResultSet.of(new String[] {"0"})));

        assertThat(reading.counter()).isNull();
        assertThat(reading.cacheHitPercent()).isEqualTo(97.5);
    }

    @Test
    void whenEveryDmvIsDeniedTheFailureIsReported() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("denied"));

        assertThatThrownBy(() -> reader.read(connection)).isInstanceOf(SQLException.class);
    }
}
