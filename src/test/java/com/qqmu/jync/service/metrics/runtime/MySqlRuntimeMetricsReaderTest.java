package com.qqmu.jync.service.metrics.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL-family presets answer the same {@code SHOW GLOBAL STATUS} names, whatever vendor
 * repackaged the protocol. A figure the server does not report stays null rather than
 * becoming zero.
 */
class MySqlRuntimeMetricsReaderTest {

    private final MySqlRuntimeMetricsReader reader = new MySqlRuntimeMetricsReader();

    @Test
    void everyFamilyPresetIsServedByThisReader() {
        for (DatabaseType type : DatabaseType.values()) {
            if (type.getFamily() == DatabaseType.DialectFamily.MYSQL) {
                assertThat(reader.supports(type)).as(type.name()).isTrue();
            }
        }
        assertThat(reader.supports(DatabaseType.POSTGRESQL)).isFalse();
        assertThat(reader.supports(DatabaseType.ORACLE)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    void theStatusRowsBecomeSessionsCounterHitRateAndLockWaits() throws SQLException {
        Connection connection = connectionReturning(
                new String[] {"Threads_connected", "12"},
                new String[] {"Questions", "900000"},
                new String[] {"Innodb_buffer_pool_read_requests", "1000"},
                new String[] {"Innodb_buffer_pool_reads", "10"},
                new String[] {"Innodb_row_lock_current_waits", "3"});

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isEqualTo(12);
        assertThat(reading.counter()).isEqualTo(900000L);
        assertThat(reading.cacheHitPercent()).isEqualTo(99.0);
        assertThat(reading.lockWaits()).isEqualTo(3);
    }

    @Test
    void theHitRateCountsTheDiskReadsInsideTheLogicalReads() throws SQLException {
        // InnoDB counts a disk read as part of the read request that caused it: 500 of 1000
        // requests served from disk is a 50% hit rate, not the 66.7% that treating the two
        // counters as disjoint would produce.
        Connection connection = connectionReturning(
                new String[] {"Threads_connected", "7"},
                new String[] {"Questions", "42"},
                new String[] {"Innodb_buffer_pool_read_requests", "1000"},
                new String[] {"Innodb_buffer_pool_reads", "500"});

        assertThat(reader.read(connection).cacheHitPercent()).isEqualTo(50.0);
    }

    @Test
    void anAbsentVariableLeavesItsFieldNull() throws SQLException {
        // A proxy in front of the server may hide the InnoDB counters entirely.
        Connection connection = connectionReturning(
                new String[] {"Threads_connected", "7"},
                new String[] {"Questions", "42"});

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isEqualTo(7);
        assertThat(reading.cacheHitPercent()).isNull();
        assertThat(reading.lockWaits()).isNull();
    }

    @Test
    void zeroCacheRequestsDoNotDivideByZero() throws SQLException {
        Connection connection = connectionReturning(
                new String[] {"Innodb_buffer_pool_read_requests", "0"},
                new String[] {"Innodb_buffer_pool_reads", "0"});

        assertThat(reader.read(connection).cacheHitPercent()).isNull();
    }

    @Test
    void aNonNumericValueIsTreatedAsUnknownInsteadOfFailingTheRow() throws SQLException {
        Connection connection = connectionReturning(
                new String[] {"Threads_connected", "ON"},
                new String[] {"Questions", "10"});

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isNull();
        assertThat(reading.counter()).isEqualTo(10L);
    }

    @Test
    void aDeniedQueryPropagatesSoTheSamplerCanRecordIt() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("denied"));

        assertThatThrownBy(() -> reader.read(connection)).isInstanceOf(SQLException.class);
    }

    private Connection connectionReturning(String[]... rows) throws SQLException {
        // Built before the stubbing begins: Mockito rejects a nested mock creation inside
        // thenReturn(...) as unfinished stubbing.
        java.sql.ResultSet resultSet = StubResultSet.of(rows);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        return connection;
    }
}
