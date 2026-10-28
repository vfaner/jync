package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The DM side of the shared session: a source that cannot mine anything yet must be an idle
 * poll, not an error that accrues APPLY_STRIKE_* and parks the project.
 */
class LogMinerSessionDmTest {

    @Test
    void anInstanceWithNothingArchivedReturnsAnIdleSession() throws SQLException {
        // Right after ARCHIVELOG is enabled V$ARCHIVED_LOG is empty. Throwing here would
        // strike the project out before the first archive switch ever happens; DM's CDC
        // latency is the archive interval, so "nothing yet" is the normal steady state of a
        // freshly configured instance.
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement archivesPs = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        ResultSet archivesRs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(DmLogMinerDialect.Q_HIGH_WATER)).thenReturn(maxRs);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getLong(1)).thenReturn(0L);
        when(maxRs.wasNull()).thenReturn(true);
        when(conn.prepareStatement(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE))
                .thenReturn(archivesPs);
        when(archivesPs.executeQuery()).thenReturn(archivesRs);
        when(archivesRs.next()).thenReturn(false);

        LogMinerSession session = LogMinerSession.open(conn, 500L, 1000,
                DmLogMinerDialect.INSTANCE);

        assertThat(session.startScn()).isEqualTo(500L);
        assertThat(session.highWaterScn())
                .as("an idle poll must not advance the resume position")
                .isEqualTo(500L);
        assertThat(session.nextBatch()).isEmpty();

        session.close();
        verify(st, never()).execute(Mockito.contains("END_LOGMNR"));
        verify(conn, never()).prepareStatement(Mockito.contains("V$LOGMNR_CONTENTS"));
    }

    @Test
    void anArchiveGapWithNothingToMineIsAlsoIdle() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement archivesPs = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        ResultSet archivesRs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(DmLogMinerDialect.Q_HIGH_WATER)).thenReturn(maxRs);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getLong(1)).thenReturn(900L);
        when(maxRs.wasNull()).thenReturn(false);
        when(conn.prepareStatement(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE))
                .thenReturn(archivesPs);
        when(archivesPs.executeQuery()).thenReturn(archivesRs);
        when(archivesRs.next()).thenReturn(false);

        LogMinerSession session = LogMinerSession.open(conn, 500L, 1000,
                DmLogMinerDialect.INSTANCE);

        assertThat(session.nextBatch()).isEmpty();
        assertThat(session.highWaterScn()).isEqualTo(500L);
        session.close();
    }

    @Test
    void miningStartsInclusiveOfTheDmStartScn() throws SQLException {
        // The cursor must be bound with "CSCN >= startScn" on DM — the resume SCN is a file
        // boundary, so the first change of the next file can sit exactly on it.
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement archivesPs = mock(PreparedStatement.class);
        PreparedStatement cursor = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        ResultSet archivesRs = mock(ResultSet.class);
        ResultSet rows = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(DmLogMinerDialect.Q_HIGH_WATER)).thenReturn(maxRs);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getLong(1)).thenReturn(900L);
        when(maxRs.wasNull()).thenReturn(false);
        when(conn.prepareStatement(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE))
                .thenReturn(archivesPs);
        when(archivesPs.executeQuery()).thenReturn(archivesRs);
        when(archivesRs.next()).thenReturn(true, false);
        when(archivesRs.getString(1)).thenReturn("/arch/a.log");
        when(archivesRs.getInt(2)).thenReturn(1);
        when(archivesRs.getLong(3)).thenReturn(7L);
        when(conn.prepareStatement(DmLogMinerDialect.INSTANCE.contentsQuery(conn)))
                .thenReturn(cursor);
        when(cursor.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        LogMinerSession session = LogMinerSession.open(conn, 500L, 1000,
                DmLogMinerDialect.INSTANCE);

        verify(cursor).setLong(1, 500L);
        verify(cursor).setFetchSize(1000);
        verify(st).execute("CALL DBMS_LOGMNR.ADD_LOGFILE('/arch/a.log', 1)");
        assertThat(session.highWaterScn()).isEqualTo(900L);
        session.close();
        verify(st).execute("CALL DBMS_LOGMNR.END_LOGMNR()");
    }

    @Test
    void oracleStillFailsLoudlyWhenNoLogCoversTheStartScn() throws SQLException {
        // waitsForArchives() is false on Oracle precisely so this stays an error: an
        // ARCHIVELOG database with no file covering the SCN means purged logs or ARCHIVELOG
        // off, and silently idling would hide a permanent gap.
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement archivesPs = mock(PreparedStatement.class);
        ResultSet scnRs = mock(ResultSet.class);
        ResultSet archivesRs = mock(ResultSet.class);
        ResultSet redoRs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(OracleLogMinerDialect.Q_VERIFY)).thenReturn(scnRs);
        when(scnRs.next()).thenReturn(true);
        when(scnRs.getLong(1)).thenReturn(900L);
        when(conn.prepareStatement(LogMinerSession.Q_ARCHIVED_LOGS_SINCE))
                .thenReturn(archivesPs);
        when(archivesPs.executeQuery()).thenReturn(archivesRs);
        when(archivesRs.next()).thenReturn(false);
        when(st.executeQuery(LogMinerSession.Q_CURRENT_REDO_MEMBERS)).thenReturn(redoRs);
        when(redoRs.next()).thenReturn(false);

        try {
            LogMinerSession.open(conn, 500L, 1000, OracleLogMinerDialect.INSTANCE);
            org.assertj.core.api.Assertions.fail("expected SQLException");
        } catch (SQLException e) {
            assertThat(e.getMessage()).contains("ARCHIVELOG");
        }
    }

    @Test
    void theDmDialectNeverSelectsTheTwelveCContentsQuery() throws SQLException {
        // No DM build exposes SRC_CON_ID; selecting it is a SQL error on the first read.
        Connection conn = mock(Connection.class);
        java.sql.DatabaseMetaData meta = mock(java.sql.DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseMajorVersion()).thenReturn(8);

        assertThat(DmLogMinerDialect.INSTANCE.contentsQuery(conn))
                .contains("0 AS SRC_CON_ID");
    }

    @Test
    void dmDiscoveryKeepsInsertionOrderAndDedupesByThreadAndSequence() throws SQLException {
        // LogMiner rejects the same (thread, sequence) twice; DM's V$ARCHIVED_LOG can list
        // the same sequence under two paths after a manual copy.
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("/arch/a.log", "/arch/a-copy.log");
        when(rs.getInt(2)).thenReturn(1, 1);
        when(rs.getLong(3)).thenReturn(7L, 7L);

        List<String> files = DmLogMinerDialect.INSTANCE.logfilesCovering(conn, 500L);

        assertThat(files).containsExactly("/arch/a.log");
    }
}
