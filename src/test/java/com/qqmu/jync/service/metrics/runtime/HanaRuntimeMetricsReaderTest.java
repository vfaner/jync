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
 * HANA offers connections and a cumulative statement count. It has no buffer-cache hit
 * ratio and no lock-wait view this build reads, so those two stay null and the page shows
 * the figures it can actually stand behind.
 */
class HanaRuntimeMetricsReaderTest {

    private final HanaRuntimeMetricsReader reader = new HanaRuntimeMetricsReader();

    @Test
    void onlyHanaIsServed() {
        assertThat(reader.supports(DatabaseType.HANA)).isTrue();
        assertThat(reader.supports(DatabaseType.MYSQL)).isFalse();
        assertThat(reader.supports(DatabaseType.ORACLE)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    void theMonitorViewsBecomeSessionsAndCounter() throws SQLException {
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"8"}),
                StubResultSet.of(new String[] {"500000"})));

        assertThat(reading.sessions()).isEqualTo(8);
        assertThat(reading.counter()).isEqualTo(500000L);
        assertThat(reading.cacheHitPercent()).isNull();
        assertThat(reading.lockWaits()).isNull();
    }

    @Test
    void aDeniedConnectionViewStillLeavesTheCounter() throws SQLException {
        Connection connection = StubConnection.returning(
                StubConnection.denied(new SQLException("insufficient privilege: M_CONNECTIONS")),
                StubConnection.statement(StubResultSet.of(new String[] {"500000"})));

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isNull();
        assertThat(reading.counter()).isEqualTo(500000L);
    }

    @Test
    void whenBothViewsAreDeniedTheFailureIsReported() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("insufficient privilege"));

        assertThatThrownBy(() -> reader.read(connection)).isInstanceOf(SQLException.class);
    }
}
