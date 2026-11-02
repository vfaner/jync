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
 * DB2 answers everything in one {@code MON_GET_DATABASE} row, so there is nothing to
 * tolerate piecewise: a denied monitor function means no reading at all that round.
 */
class Db2RuntimeMetricsReaderTest {

    private final Db2RuntimeMetricsReader reader = new Db2RuntimeMetricsReader();

    @Test
    void onlyTheDb2FamilyIsServed() {
        for (DatabaseType type : DatabaseType.values()) {
            if (type.getFamily() == DatabaseType.DialectFamily.DB2) {
                assertThat(reader.supports(type)).as(type.name()).isTrue();
            }
        }
        assertThat(reader.supports(DatabaseType.POSTGRESQL)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    void theMonitorRowBecomesSessionsCounterAndHitRate() throws SQLException {
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"5", "1000", "1000", "10"})));

        assertThat(reading.sessions()).isEqualTo(5);
        assertThat(reading.counter()).isEqualTo(1000L);
        assertThat(reading.cacheHitPercent()).isEqualTo(99.0);
        // This query asks for no lock-wait figure, so the page shows "unknown" rather than 0.
        assertThat(reading.lockWaits()).isNull();
    }

    @Test
    void theHitRateCountsThePhysicalReadsInsideTheLogicalReads() throws SQLException {
        // A physical read is one of the logical reads that had to go to disk: 500 of 1000
        // logical reads is a 50% hit rate, not 66.7%.
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"5", "1000", "1000", "500"})));

        assertThat(reading.cacheHitPercent()).isEqualTo(50.0);
    }

    @Test
    void zeroPoolReadsDoNotDivideByZero() throws SQLException {
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(
                StubResultSet.of(new String[] {"0", "0", "0", "0"})));

        assertThat(reading.cacheHitPercent()).isNull();
    }

    @Test
    void anEmptyResultLeavesEveryFigureUnknown() throws SQLException {
        RuntimeMetricsReader.Reading reading = reader.read(StubConnection.returning(StubResultSet.of()));

        assertThat(reading.sessions()).isNull();
        assertThat(reading.counter()).isNull();
        assertThat(reading.cacheHitPercent()).isNull();
    }

    @Test
    void aDeniedMonitorFunctionPropagatesSoTheSamplerCanRecordIt() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("SQL0551N"));

        assertThatThrownBy(() -> reader.read(connection)).isInstanceOf(SQLException.class);
    }
}
