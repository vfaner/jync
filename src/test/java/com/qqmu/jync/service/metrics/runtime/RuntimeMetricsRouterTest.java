package com.qqmu.jync.service.metrics.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/** Routing is by product family; a product without a reader is a normal, empty answer. */
class RuntimeMetricsRouterTest {

    private final RuntimeMetricsReader mysql = mock(RuntimeMetricsReader.class);
    private final RuntimeMetricsReader postgres = mock(RuntimeMetricsReader.class);

    private final RuntimeMetricsRouter router =
            new RuntimeMetricsRouter(List.of(mysql, postgres));

    @Test
    void aMysqlFamilyProductGoesToTheMysqlReader() throws Exception {
        Connection connection = mock(Connection.class);
        RuntimeMetricsReader.Reading reading = new RuntimeMetricsReader.Reading(1, 2L, 3.0, 4);
        when(mysql.supports(DatabaseType.MYSQL)).thenReturn(true);
        when(mysql.read(connection)).thenReturn(reading);

        assertThat(router.read(DatabaseType.MYSQL, connection)).contains(reading);
        verify(postgres, never()).read(connection);
    }

    @Test
    void aPostgresFamilyProductGoesToThePostgresReader() throws Exception {
        Connection connection = mock(Connection.class);
        RuntimeMetricsReader.Reading reading = new RuntimeMetricsReader.Reading(1, 2L, 3.0, 4);
        when(mysql.supports(DatabaseType.KINGBASE)).thenReturn(false);
        when(postgres.supports(DatabaseType.KINGBASE)).thenReturn(true);
        when(postgres.read(connection)).thenReturn(reading);

        assertThat(router.read(DatabaseType.KINGBASE, connection)).contains(reading);
    }

    @Test
    void aProductWithoutAReaderAnswersEmpty() throws Exception {
        Connection connection = mock(Connection.class);
        when(mysql.supports(DatabaseType.CLICKHOUSE)).thenReturn(false);
        when(postgres.supports(DatabaseType.CLICKHOUSE)).thenReturn(false);

        Optional<RuntimeMetricsReader.Reading> reading =
                router.read(DatabaseType.CLICKHOUSE, connection);

        assertThat(reading).isEmpty();
        verify(mysql, never()).read(connection);
        verify(postgres, never()).read(connection);
    }
}
