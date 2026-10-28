package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

    /**
     * 真库实测:openGauss 驱动的 {@code getDatabaseProductName()} 返回 "PostgreSQL",
     * 只看它会让方言判定落到 POSTGRES 分支,消费端第一条 SQL 就因
     * {@code type "pg_lsn" does not exist} 失败。{@code SELECT version()} 才是证据。
     */
    @Test
    void openGaussBehindAPostgreSQLProductNameIsStillDetected() throws SQLException {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("(openGauss 5.0.0 build ) compiled at 2023-04-18");
        when(st.executeQuery(anyString())).thenReturn(rs);

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
