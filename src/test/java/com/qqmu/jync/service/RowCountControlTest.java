package com.qqmu.jync.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class RowCountControlTest {

    @Test
    void cancelStopsEveryRegisteredStatement() throws SQLException {
        RowCountControl control = new RowCountControl();
        Statement first = mock(Statement.class);
        Statement second = mock(Statement.class);

        control.register(first);
        control.register(second);
        control.cancel();

        verify(first).cancel();
        verify(second).cancel();
    }

    @Test
    void aStatementRegisteredAfterCancelIsStoppedImmediately() throws SQLException {
        RowCountControl control = new RowCountControl();
        control.cancel();
        Statement late = mock(Statement.class);

        control.register(late);

        verify(late).cancel();
    }

    @Test
    void anUnregisteredStatementIsNoLongerCancelled() throws SQLException {
        RowCountControl control = new RowCountControl();
        Statement closed = mock(Statement.class);
        control.register(closed);
        control.unregister(closed);

        control.cancel();

        verifyNoInteractions(closed);
    }
}
