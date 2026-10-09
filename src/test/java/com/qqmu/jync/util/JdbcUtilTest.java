package com.qqmu.jync.util;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * P2: query-timeout application — propagated when configured, silently skipped when the driver
 * refuses or no timeout was set.
 */
class JdbcUtilTest {

    @Test
    void appliesTheTimeoutWhenPositive() throws SQLException {
        Statement statement = Mockito.mock(Statement.class);

        JdbcUtil.applyQueryTimeout(statement, 300);

        verify(statement).setQueryTimeout(eq(300));
    }

    @Test
    void doesNothingForZeroNullOrNullStatement() throws SQLException {
        Statement statement = Mockito.mock(Statement.class);

        JdbcUtil.applyQueryTimeout(statement, 0);
        JdbcUtil.applyQueryTimeout(statement, null);
        JdbcUtil.applyQueryTimeout(null, 300);

        verify(statement, never()).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aDriverRejectingSetQueryTimeoutDoesNotFailTheCall() throws SQLException {
        Statement statement = Mockito.mock(Statement.class);
        Mockito.doThrow(new SQLException("unsupported")).when(statement).setQueryTimeout(60);

        JdbcUtil.applyQueryTimeout(statement, 60); // must not throw
    }
}
