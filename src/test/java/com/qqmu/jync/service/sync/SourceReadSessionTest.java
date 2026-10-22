package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/**
 * The source read transaction exists for one reason: PostgreSQL's driver only fetches rows
 * one at a time when autocommit is off. It must therefore be reversible — a connection that
 * came in with autocommit off belongs to its caller and is never committed or restored — and
 * it must never turn a read-only cleanup failure into a failed copy.
 */
class SourceReadSessionTest {

    @Test
    void aRequiredSessionTurnsAutocommitOffCommitsAndRestoresIt() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(true);

        SourceReadSession session = SourceReadSession.begin(conn, true);
        verify(conn).setAutoCommit(false);
        assertThat(session.opened()).isTrue();

        session.commit();
        verify(conn).commit();

        session.close();
        verify(conn).setAutoCommit(true);
    }

    @Test
    void anUnrequiredSessionNeverTouchesTheConnection() throws SQLException {
        Connection conn = mock(Connection.class);

        SourceReadSession session = SourceReadSession.begin(conn, false);
        session.commit();
        session.rollback();
        session.close();

        assertThat(session.opened()).isFalse();
        verifyNoInteractions(conn);
    }

    @Test
    void aConnectionAlreadyInATransactionBelongsToItsCaller() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(false);

        SourceReadSession session = SourceReadSession.begin(conn, true);
        session.commit();
        session.close();

        assertThat(session.opened()).isFalse();
        verify(conn, never()).commit();
        verify(conn, never()).rollback();
        verify(conn, never()).setAutoCommit(anyBoolean());
    }

    @Test
    void closingWithoutCommitRollsBackAndRestoresAutocommit() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(true);

        SourceReadSession.begin(conn, true).close();

        verify(conn).rollback();
        verify(conn).setAutoCommit(true);
    }

    @Test
    void aFailedCommitIsNotAllowedToFailTheCopy() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("connection reset")).when(conn).commit();

        SourceReadSession session = SourceReadSession.begin(conn, true);
        session.commit();   // must not throw: the rows are already read
        session.close();

        verify(conn).rollback();
        verify(conn).setAutoCommit(true);
    }

    @Test
    void aFailedAutocommitRestoreIsNotAllowedToFailTheCopy() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("closed")).when(conn).setAutoCommit(true);

        SourceReadSession session = SourceReadSession.begin(conn, true);
        session.commit();
        session.close();    // must not throw
    }

    @Test
    void closeIsIdempotentAndQuietAfterCommit() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.getAutoCommit()).thenReturn(true);

        SourceReadSession session = SourceReadSession.begin(conn, true);
        session.commit();
        session.close();
        session.close();

        verify(conn, times(1)).commit();
        verify(conn, never()).rollback();
        verify(conn, times(1)).setAutoCommit(true);
    }
}
