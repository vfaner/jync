package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class PgLogicalCdcConsumerDialectTest {

    @Test
    void dialectComesFromTheServerNotThePreset() throws SQLException {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("openGauss");

        assertThat(PgLogicalCdcConsumer.dialectOf(conn)).isEqualTo(PgLogicalDialect.OPENGAUSS);
    }

    @Test
    void metadataFailureFallsBackToVanillaPostgres() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenThrow(new SQLException("boom"));

        assertThat(PgLogicalCdcConsumer.dialectOf(conn)).isEqualTo(PgLogicalDialect.POSTGRES);
    }

    @Test
    void nullMetadataFallsBackToVanillaPostgres() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(null);

        assertThat(PgLogicalCdcConsumer.dialectOf(conn)).isEqualTo(PgLogicalDialect.POSTGRES);
    }
}
