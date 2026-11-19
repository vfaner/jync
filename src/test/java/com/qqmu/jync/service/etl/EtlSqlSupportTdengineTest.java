package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * The TDengine dictionary answers a missing table with SQLState "" and vendor code 9731
 * (0x2603), so only the code identifies it as "no such table".
 */
class EtlSqlSupportTdengineTest {

    @Test
    void theTdengineMissingTableCodeMeansMissing() throws Exception {
        Connection conn = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(conn.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(
                new SQLException("internal error 0x2603", "", 9731));

        assertThat(EtlSqlSupport.exists(conn, "iot.events")).isFalse();
    }

    @Test
    void anotherFailureIsStillRaised() throws Exception {
        Connection conn = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(conn.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(
                new SQLException("permission denied", "", 9750));

        assertThatThrownBy(() -> EtlSqlSupport.exists(conn, "iot.events"))
                .isInstanceOf(SQLException.class);
    }
}
