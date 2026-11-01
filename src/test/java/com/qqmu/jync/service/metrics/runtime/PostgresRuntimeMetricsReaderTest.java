package com.qqmu.jync.service.metrics.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The PostgreSQL family exposes sessions, cache reads and locks through ordinary catalog
 * views. The lock count is an optional extra: losing it must not cost the sessions figure.
 */
class PostgresRuntimeMetricsReaderTest {

    private final PostgresRuntimeMetricsReader reader = new PostgresRuntimeMetricsReader();

    @Test
    void everyFamilyPresetIsServedByThisReader() {
        for (DatabaseType type : DatabaseType.values()) {
            if (type.getFamily() == DatabaseType.DialectFamily.POSTGRES) {
                assertThat(reader.supports(type)).as(type.name()).isTrue();
            }
        }
        assertThat(reader.supports(DatabaseType.MYSQL)).isFalse();
        assertThat(reader.supports(DatabaseType.ORACLE)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    void theCatalogRowBecomesSessionsCounterAndHitRate() throws SQLException {
        java.sql.ResultSet catalog = StubResultSet.of(new String[] {"15", "60000", "9800", "200"});
        java.sql.ResultSet locks = StubResultSet.of(new String[] {"4"});
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(catalog).thenReturn(locks);

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isEqualTo(15);
        assertThat(reading.counter()).isEqualTo(60000L);
        assertThat(reading.cacheHitPercent()).isEqualTo(98.0);
        assertThat(reading.lockWaits()).isEqualTo(4);
    }

    @Test
    void aDeniedLockViewStillLeavesTheSessionsAndCounter() throws SQLException {
        java.sql.ResultSet catalog = StubResultSet.of(new String[] {"15", "60000", "0", "0"});
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString()))
                .thenReturn(catalog)
                .thenThrow(new SQLException("permission denied for pg_locks"));

        RuntimeMetricsReader.Reading reading = reader.read(connection);

        assertThat(reading.sessions()).isEqualTo(15);
        assertThat(reading.lockWaits()).isNull();
        // A hit rate over zero reads is not 0%, it is unknown.
        assertThat(reading.cacheHitPercent()).isNull();
    }

    @Test
    void aDeniedMainViewPropagatesSoTheSamplerCanRecordIt() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("denied"));

        assertThatThrownBy(() -> reader.read(connection)).isInstanceOf(SQLException.class);
    }

    @Test
    void theLockQueryIsOnlyAskedOnce() throws SQLException {
        java.sql.ResultSet catalog = StubResultSet.of(new String[] {"1", "2", "3", "1"});
        java.sql.ResultSet locks = StubResultSet.of(new String[] {"0"});
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(catalog).thenReturn(locks);

        reader.read(connection);

        verify(statement, times(2)).executeQuery(anyString());
    }
}
