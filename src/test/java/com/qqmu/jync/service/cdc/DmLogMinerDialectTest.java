package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * The DM side of the LogMiner seam. Every assertion here pins a command that was measured
 * against DM8 (docs/dm-logminer-spike.md) — the shapes are DM-only and a regression to the
 * Oracle form fails on a real database with a DM syntax error, not silently.
 */
class DmLogMinerDialectTest {

    private static Fixture fixture() throws SQLException {
        return new Fixture();
    }

    /** Mock connection answering the three DM catalog queries this dialect issues. */
    private static final class Fixture {
        final Connection conn = mock(Connection.class);
        final Statement st = mock(Statement.class);
        final PreparedStatement archivesPs = mock(PreparedStatement.class);
        final ResultSet archivesRs = mock(ResultSet.class);
        final ResultSet maxRs = mock(ResultSet.class);

        Fixture() throws SQLException {
            when(conn.createStatement()).thenReturn(st);
            when(st.executeQuery(DmLogMinerDialect.Q_HIGH_WATER)).thenReturn(maxRs);
            when(conn.prepareStatement(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE))
                    .thenReturn(archivesPs);
            when(archivesPs.executeQuery()).thenReturn(archivesRs);
        }

        void archives(String... names) throws SQLException {
            if (names.length == 0) {
                when(archivesRs.next()).thenReturn(false);
                return;
            }
            Boolean[] answers = new Boolean[names.length + 1];
            for (int i = 0; i < names.length; i++) {
                answers[i] = Boolean.TRUE;
            }
            answers[names.length] = Boolean.FALSE;
            when(archivesRs.next()).thenReturn(answers[0],
                    java.util.Arrays.copyOfRange(answers, 1, answers.length));
            String[] tail = new String[names.length - 1];
            System.arraycopy(names, 1, tail, 0, tail.length);
            when(archivesRs.getString(1)).thenReturn(names[0], tail);
            Integer[] threads = new Integer[names.length];
            Long[] sequences = new Long[names.length];
            for (int i = 0; i < names.length; i++) {
                threads[i] = 1;
                sequences[i] = 200L + i;
            }
            when(archivesRs.getInt(2)).thenReturn(threads[0],
                    java.util.Arrays.copyOfRange(threads, 1, threads.length));
            when(archivesRs.getLong(3)).thenReturn(sequences[0],
                    java.util.Arrays.copyOfRange(sequences, 1, sequences.length));
        }

        void highWater(long nextChange) throws SQLException {
            when(maxRs.next()).thenReturn(true);
            when(maxRs.getLong(1)).thenReturn(nextChange);
        }
    }

    @Test
    void highWaterIsTheNewestArchivedFilesNextChange() throws SQLException {
        // DM's V$DATABASE has no CURRENT_SCN (spike §1.2). The newest archived file's
        // NEXT_CHANGE# is the first SCN that is NOT yet minable, so it is the ceiling.
        Fixture fx = fixture();
        fx.highWater(43992L);

        assertThat(DmLogMinerDialect.INSTANCE.highWaterScn(fx.conn)).isEqualTo(43992L);
        verify(fx.st).executeQuery("SELECT MAX(NEXT_CHANGE#) FROM V$ARCHIVED_LOG");
    }

    @Test
    void anInstanceWithNoArchivedLogsHasNoHighWater() throws SQLException {
        // ARCHIVELOG enabled but nothing archived yet, or ARCH_MODE off: MAX() is NULL.
        // Returning 0 would look like a valid SCN and make the pump scan from way back.
        Fixture fx = fixture();
        when(fx.maxRs.next()).thenReturn(true);
        when(fx.maxRs.getLong(1)).thenReturn(0L);
        when(fx.maxRs.wasNull()).thenReturn(true);

        assertThat(DmLogMinerDialect.INSTANCE.highWaterScn(fx.conn))
                .isEqualTo(DmLogMinerDialect.NO_HIGH_WATER);
    }

    @Test
    void logfilesCoveringSkipsTheOracleOnlyDestIdPredicate() throws SQLException {
        // V$ARCHIVED_LOG.DEST_ID is NULL on every DM row (spike §2.3); Oracle's
        // "DEST_ID = 1" filter would silently return nothing forever.
        Fixture fx = fixture();
        fx.archives("/arch/a.log", "/arch/b.log");

        List<String> files = DmLogMinerDialect.INSTANCE.logfilesCovering(fx.conn, 400L);

        assertThat(files).containsExactly("/arch/a.log", "/arch/b.log");
        assertThat(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE).doesNotContain("DEST_ID");
        assertThat(DmLogMinerDialect.Q_ARCHIVED_LOGS_SINCE).contains("V$ARCHIVED_LOG");
        verify(fx.archivesPs).setLong(1, 400L);
    }

    @Test
    void theCurrentOnlineRedoIsNeverAdded() throws SQLException {
        // DM answers [-4546] File open failure for anything under V$RLOGFILE: only archived
        // files can be mined (spike §1.2). The dialect must not query the redo members at
        // all, otherwise a later refactor could quietly reintroduce them.
        Fixture fx = fixture();
        fx.archives("/arch/a.log");

        DmLogMinerDialect.INSTANCE.logfilesCovering(fx.conn, 400L);

        verify(fx.conn, never()).prepareStatement(Mockito.contains("V$LOGFILE"));
        verify(fx.st, never()).executeQuery(Mockito.contains("V$LOGFILE"));
    }

    @Test
    void addLogfileUsesThePositionalCallWithAddOption() throws SQLException {
        // DM has no DBMS_LOGMNR.NEW/ADDFILE constants ([-2007] Syntax error in both the
        // BEGIN…END and CALL forms). ADD_LOGFILE(path, 1) adds; option 2 REMOVES ([-2849]).
        Fixture fx = fixture();

        DmLogMinerDialect.INSTANCE.startMining(fx.conn,
                List.of("/arch/a.log", "/arch/b.log"), 400L, 900L);

        verify(fx.st).execute("CALL DBMS_LOGMNR.ADD_LOGFILE('/arch/a.log', 1)");
        verify(fx.st).execute("CALL DBMS_LOGMNR.ADD_LOGFILE('/arch/b.log', 1)");
    }

    @Test
    void addLogfileEscapesAQuoteInThePath() throws SQLException {
        Fixture fx = fixture();

        DmLogMinerDialect.INSTANCE.startMining(fx.conn, List.of("/arch/o'brien.log"), 400L, 900L);

        verify(fx.st).execute("CALL DBMS_LOGMNR.ADD_LOGFILE('/arch/o''brien.log', 1)");
    }

    @Test
    void startMiningBoundsTheRangeAndNamesTheDictionaryAndCommittedFlags() throws SQLException {
        // Measured: START_LOGMNR accepts startScn/endScn by name on DM8. Both flags are
        // load-bearing — without DICT_FROM_ONLINE_CATALOG the SQL_REDO columns come out as
        // "COL 1"; without COMMITTED_DATA_ONLY uncommitted and aborted rows are mined.
        Fixture fx = fixture();

        DmLogMinerDialect.INSTANCE.startMining(fx.conn, List.of("/arch/a.log"), 400L, 900L);

        verify(fx.st).execute(Mockito.argThat((String sql) ->
                sql.startsWith("CALL DBMS_LOGMNR.START_LOGMNR(")
                        && sql.contains("startScn => 400")
                        && sql.contains("endScn => 900")
                        && sql.contains("DICT_FROM_ONLINE_CATALOG")
                        && sql.contains("COMMITTED_DATA_ONLY")));
    }

    @Test
    void startMiningWithNoFilesIssuesNoCommands() throws SQLException {
        Fixture fx = fixture();

        DmLogMinerDialect.INSTANCE.startMining(fx.conn, List.of(), 400L, 900L);

        verify(fx.st, never()).execute(anyString());
    }

    @Test
    void endMiningUsesTheCallForm() throws SQLException {
        Fixture fx = fixture();

        DmLogMinerDialect.INSTANCE.endMining(fx.conn);

        verify(fx.st).execute("CALL DBMS_LOGMNR.END_LOGMNR()");
    }

    @Test
    void endMiningSwallowsFailures() throws SQLException {
        Fixture fx = fixture();
        when(fx.st.execute(anyString())).thenThrow(new SQLException("no session"));

        DmLogMinerDialect.INSTANCE.endMining(fx.conn);   // must not throw
    }

    @Test
    void theContentsQueryIsInclusiveOfTheStartScn() throws SQLException {
        // DM's high-water is a file boundary (MAX(NEXT_CHANGE#)): the next file's first
        // commit can land exactly ON that SCN. Oracle's "CSCN > startScn" would drop it, so
        // the DM query must compare with >=.
        String sql = DmLogMinerDialect.INSTANCE.contentsQuery(mock(Connection.class));

        assertThat(sql)
                .contains("FROM V$LOGMNR_CONTENTS")
                .contains("CSCN >= ?")
                .contains("RS_ID, CSCN,")
                .contains("0 AS SRC_CON_ID")
                .doesNotContain("SRC_CON_ID,");
    }

    @Test
    void anEmptyArchiveListIsANormalWaitingStateNotAMisconfiguration() {
        assertThat(DmLogMinerDialect.INSTANCE.waitsForArchives()).isTrue();
        assertThat(OracleLogMinerDialect.INSTANCE.waitsForArchives()).isFalse();
    }

    @Test
    void verifyProbesTheDmOnlyRedoFileView() throws SQLException {
        Fixture fx = fixture();
        ResultSet probe = mock(ResultSet.class);
        when(fx.st.executeQuery(DmLogMinerDialect.Q_VERIFY)).thenReturn(probe);
        when(probe.next()).thenReturn(true);

        DmLogMinerDialect.INSTANCE.verify(fx.conn);

        verify(fx.st).executeQuery("SELECT COUNT(*) FROM V$RLOGFILE");
    }

    @Test
    void verifyFailsWhenTheRedoFileViewIsMissing() throws SQLException {
        Fixture fx = fixture();
        when(fx.st.executeQuery(DmLogMinerDialect.Q_VERIFY))
                .thenThrow(new SQLException("[-2106] Invalid view name"));

        assertThat(catchSqlException(() -> DmLogMinerDialect.INSTANCE.verify(fx.conn)))
                .contains("V$RLOGFILE");
    }

    @Test
    void prepareSessionIssuesNoNlsStatements() throws SQLException {
        // DM has no NLS_DATE_FORMAT/... session parameters; ALTER SESSION would fail the
        // whole cycle if it inherited Oracle's pinning.
        Fixture fx = fixture();

        DmLogMinerDialect.INSTANCE.prepareSession(fx.conn);

        verify(fx.st, never()).execute(Mockito.contains("NLS"));
        verify(fx.conn, never()).createStatement(anyInt(), anyInt());
    }

    private static String catchSqlException(SqlRunnable runnable) {
        try {
            runnable.run();
            return "";
        } catch (SQLException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}
