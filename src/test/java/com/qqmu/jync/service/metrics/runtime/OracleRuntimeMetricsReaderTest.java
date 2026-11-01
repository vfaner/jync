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
 * Oracle, DM and YashanDB expose sessions, execution counts and locks through {@code v$}
 * views, but not identically — and a monitoring role often lacks one of them. Losing the
 * lock figure must not cost the sessions figure; losing everything is a failure worth
 * recording.
 */
class OracleRuntimeMetricsReaderTest {

    private final OracleRuntimeMetricsReader reader = new OracleRuntimeMetricsReader();

    @Test
    void everyFamilyPresetIsServedByThisReader() {
        for (DatabaseType type : DatabaseType.values()) {
            if (type.getFamily() == DatabaseType.DialectFamily.ORACLE) {
                assertThat(reader.supports(type)).as(type.name()).isTrue();
            }
        }
        assertThat(reader.supports(DatabaseType.MYSQL)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    void theDictionaryRowsBecomeSessionsCounterHitRateAndLocks() throws SQLException {
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"23"}),
                StubResultSet.of(
                        new String[] {"execute count", "700000"},
                        new String[] {"session logical reads", "99000"},
                        new String[] {"physical reads", "1000"}),
                StubResultSet.of(new String[] {"2"})));

        assertThat(reading.sessions()).isEqualTo(23);
        assertThat(reading.counter()).isEqualTo(700000L);
        assertThat(reading.cacheHitPercent()).isEqualTo(99.0);
        assertThat(reading.lockWaits()).isEqualTo(2);
    }

    @Test
    void yashanStyleStatisticNamesAreUnderstood() throws SQLException {
        // Verified against YashanDB 23.4: upper-case names, and BUFFER GETS / DISK READS
        // where Oracle says SESSION LOGICAL READS / PHYSICAL READS.
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"27"}),
                StubResultSet.of(
                        new String[] {"EXECUTE COUNT", "3701"},
                        new String[] {"BUFFER GETS", "9900"},
                        new String[] {"DISK READS", "100"}),
                StubResultSet.of(new String[] {"0"})));

        assertThat(reading.sessions()).isEqualTo(27);
        assertThat(reading.counter()).isEqualTo(3701L);
        assertThat(reading.cacheHitPercent()).isEqualTo(99.0);
    }

    @Test
    void anUnreadableLockViewStillYieldsSessionsAndCounter() throws SQLException {
        Connection connection = StubConnection.returning(
                StubConnection.statement(StubResultSet.of(new String[] {"23"})),
                StubConnection.statement(StubResultSet.of(new String[] {"execute count", "700000"})),
                StubConnection.denied(new SQLException("ORA-00942: table or view does not exist")));

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isEqualTo(23);
        assertThat(reading.counter()).isEqualTo(700000L);
        assertThat(reading.lockWaits()).isNull();
        assertThat(reading.cacheHitPercent()).isNull();
    }

    @Test
    void whenEveryViewIsDeniedTheFailureIsReported() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("ORA-00942"));

        assertThatThrownBy(() -> reader.read(connection)).isInstanceOf(SQLException.class);
    }
}
