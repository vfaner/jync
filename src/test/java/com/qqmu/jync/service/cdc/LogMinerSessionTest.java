package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * LogMinerSession open-sequence and RS_ID continuation-merge tests. All JDBC is mocked —
 * the real ARCHIVELOG pipeline is covered by Task 7 ITs.
 */
class LogMinerSessionTest {

    /** One V$ARCHIVED_LOG row: the file plus the identity LogMiner deduplicates by. */
    private record Archived(String name, int thread, long sequence) {
    }

    /** One V$LOGFILE/V$LOG row for a CURRENT redo group. */
    private record Redo(int group, String member, int thread, long sequence) {
    }

    /**
     * Wire a mock connection that answers: CURRENT_SCN, archived-log list (bind?), current
     * redo members, and the V$LOGMNR_CONTENTS cursor. Returns the cursor so tests can drive
     * nextBatch.
     */
    private static final class Fixture {
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData meta = mock(DatabaseMetaData.class);
        final Statement st = mock(Statement.class);
        final PreparedStatement cursor = mock(PreparedStatement.class);
        final PreparedStatement archivePs = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final ResultSet scnRs = mock(ResultSet.class);
        final ResultSet archiveRs = mock(ResultSet.class);
        final ResultSet redoRs = mock(ResultSet.class);

        Fixture(long currentScn, String[] archivedLogs, String[] redoMembers) throws SQLException {
            this(currentScn, archivedLogs, redoMembers, 23);
        }

        Fixture(long currentScn, String[] archivedLogs, String[] redoMembers,
                int databaseMajorVersion) throws SQLException {
            this(currentScn, archived(archivedLogs), redo(redoMembers), databaseMajorVersion);
        }

        Fixture(long currentScn, Archived[] archivedLogs, Redo[] redoMembers)
                throws SQLException {
            this(currentScn, archivedLogs, redoMembers, 23);
        }

        Fixture(long currentScn, Archived[] archivedLogs, Redo[] redoMembers,
                int databaseMajorVersion) throws SQLException {
            when(conn.getMetaData()).thenReturn(meta);
            when(meta.getDatabaseMajorVersion()).thenReturn(databaseMajorVersion);
            when(conn.createStatement()).thenReturn(st);
            when(st.executeQuery(LogMinerSession.Q_CURRENT_SCN)).thenReturn(scnRs);
            when(scnRs.next()).thenReturn(true);
            when(scnRs.getLong(1)).thenReturn(currentScn);

            // Archived logs query — since v1.7.1 it is a PreparedStatement with the SCN
            // bound (instead of a string-substituted Statement.executeQuery). The result
            // carries NAME, THREAD# and SEQUENCE# so the open sequence can tell whether the
            // current online member is already covered by an archived copy.
            when(conn.prepareStatement(LogMinerSession.Q_ARCHIVED_LOGS_SINCE))
                    .thenReturn(archivePs);
            when(archivePs.executeQuery()).thenReturn(archiveRs);
            stubRows(archiveRs, archivedLogs.length);
            if (archivedLogs.length > 0) {
                when(archiveRs.getString(1)).thenReturn(archivedLogs[0].name(),
                        archivedNames(archivedLogs));
                when(archiveRs.getInt(2)).thenReturn(archivedLogs[0].thread(),
                        archivedThreads(archivedLogs));
                when(archiveRs.getLong(3)).thenReturn(archivedLogs[0].sequence(),
                        archivedSequences(archivedLogs));
            }

            when(st.executeQuery(LogMinerSession.Q_CURRENT_REDO_MEMBERS)).thenReturn(redoRs);
            stubRows(redoRs, redoMembers.length);
            if (redoMembers.length > 0) {
                // getString(1) keeps the pre-dedupe contract readable during the RED step;
                // the production query returns GROUP#, MEMBER, THREAD#, SEQUENCE#.
                when(redoRs.getString(1)).thenReturn(redoMembers[0].member(),
                        redoMembers(redoMembers));
                when(redoRs.getInt(1)).thenReturn(redoMembers[0].group(), redoGroups(redoMembers));
                when(redoRs.getString(2)).thenReturn(redoMembers[0].member(),
                        redoMembers(redoMembers));
                when(redoRs.getInt(3)).thenReturn(redoMembers[0].thread(),
                        redoThreads(redoMembers));
                when(redoRs.getLong(4)).thenReturn(redoMembers[0].sequence(),
                        redoSequences(redoMembers));
            }

            when(conn.prepareStatement(LogMinerSession.Q_CONTENTS)).thenReturn(cursor);
            when(cursor.executeQuery()).thenReturn(rows);
        }

        private static void stubRows(ResultSet rs, int count) throws SQLException {
            if (count == 0) {
                when(rs.next()).thenReturn(false);
                return;
            }
            Boolean[] answers = new Boolean[count + 1];
            for (int i = 0; i < count; i++) {
                answers[i] = Boolean.TRUE;
            }
            answers[count] = Boolean.FALSE;
            when(rs.next()).thenReturn(answers[0],
                    java.util.Arrays.copyOfRange(answers, 1, answers.length));
        }

        /** Sequence below the redo defaults so plain fixtures never look like an overlap. */
        private static Archived[] archived(String[] names) {
            Archived[] out = new Archived[names.length];
            for (int i = 0; i < names.length; i++) {
                out[i] = new Archived(names[i], 1, 100L + i);
            }
            return out;
        }

        private static Redo[] redo(String[] members) {
            Redo[] out = new Redo[members.length];
            for (int i = 0; i < members.length; i++) {
                out[i] = new Redo(1, members[i], 1, 1000L + i);
            }
            return out;
        }

        private static String[] archivedNames(Archived[] rows) {
            String[] out = new String[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].name();
            }
            return out;
        }

        private static Integer[] archivedThreads(Archived[] rows) {
            Integer[] out = new Integer[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].thread();
            }
            return out;
        }

        private static Long[] archivedSequences(Archived[] rows) {
            Long[] out = new Long[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].sequence();
            }
            return out;
        }

        private static String[] redoMembers(Redo[] rows) {
            String[] out = new String[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].member();
            }
            return out;
        }

        private static Integer[] redoGroups(Redo[] rows) {
            Integer[] out = new Integer[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].group();
            }
            return out;
        }

        private static Integer[] redoThreads(Redo[] rows) {
            Integer[] out = new Integer[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].thread();
            }
            return out;
        }

        private static Long[] redoSequences(Redo[] rows) {
            Long[] out = new Long[rows.length - 1];
            for (int i = 1; i < rows.length; i++) {
                out[i - 1] = rows[i].sequence();
            }
            return out;
        }
    }

    @Test
    void aCurrentLogPathAlreadyReturnedByTheArchivedQueryIsAddedOnce() throws SQLException {
        // V$ARCHIVED_LOG also carries a row for the current redo group, and on a FRA
        // database that row's NAME is the online member path. Adding it a second time from
        // V$LOGFILE raises ORA-01289 on a live database.
        Fixture fx = new Fixture(1000L,
                new Archived[]{new Archived("/arch/1.log", 1, 100L),
                        new Archived("/redo/cur.log", 1, 101L)},
                new Redo[]{new Redo(1, "/redo/cur.log", 1, 101L)});

        LogMinerSession.openDefault(fx.conn, 500L, 1000).close();

        verify(fx.st, times(2)).execute(Mockito.contains("DBMS_LOGMNR.ADD_LOGFILE"));
        verify(fx.st, times(1)).execute(Mockito.argThat(sql -> sql.contains("/redo/cur.log")));
    }

    @Test
    void aMultiplexedCurrentGroupContributesOnlyOneMember() throws SQLException {
        // Oracle only requires one member of a multiplexed group; LogMiner identifies the
        // group by thread+sequence, so the second member is a duplicate (ORA-01289).
        Fixture fx = new Fixture(1000L,
                new Archived[]{new Archived("/arch/1.log", 1, 100L)},
                new Redo[]{new Redo(1, "/redo/m1.log", 1, 101L),
                        new Redo(1, "/redo/m2.log", 1, 101L)});

        LogMinerSession.openDefault(fx.conn, 500L, 1000).close();

        verify(fx.st, times(2)).execute(Mockito.contains("DBMS_LOGMNR.ADD_LOGFILE"));
        verify(fx.st, never()).execute(Mockito.contains("/redo/m2.log"));
    }

    @Test
    void anOnlineMemberWhoseSequenceIsAlreadyArchivedIsNotAddedAgain() throws SQLException {
        // ARCHIVE LOG CURRENT archives the group without switching it: the archived copy
        // covers the sequence while V$LOGFILE still reports the online member for the same
        // thread+sequence. Adding the online path again is the duplicate ORA-01289 rejects.
        Fixture fx = new Fixture(1000L,
                new Archived[]{new Archived("/arch/1_101.arc", 1, 101L)},
                new Redo[]{new Redo(1, "/redo/cur.log", 1, 101L)});

        LogMinerSession.openDefault(fx.conn, 500L, 1000).close();

        verify(fx.st, times(1)).execute(Mockito.contains("DBMS_LOGMNR.ADD_LOGFILE"));
        verify(fx.st, never()).execute(Mockito.contains("/redo/cur.log"));
    }

    @Test
    void openSequenceReadsScnAddsLogsAndStartsLogmnr() throws SQLException {
        Fixture fx = new Fixture(1000L,
                new String[]{"/oracle/arch/1_1.log", "/oracle/arch/1_2.log"},
                new String[]{"/oracle/redo/current01.log"});

        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);

        assertThat(session.startScn()).isEqualTo(500L);
        assertThat(session.highWaterScn()).isEqualTo(1000L);

        // ADD_LOGFILE called once per logfile (3 total: 2 archives + 1 online), NEW on the first.
        verify(fx.st, times(3)).execute(Mockito.contains("DBMS_LOGMNR.ADD_LOGFILE"));
        verify(fx.st).execute(Mockito.contains("NEW"));
        verify(fx.st, times(2)).execute(Mockito.contains("ADDFILE"));
        // START_LOGMNR carries startScn, currentScn and the two option flags.
        verify(fx.st).execute(Mockito.argThat((String sql) ->
                sql.contains("START_LOGMNR") && sql.contains("startScn => 500")
                        && sql.contains("endScn => 1000")
                        && sql.contains("DICT_FROM_ONLINE_CATALOG")
                        && sql.contains("COMMITTED_DATA_ONLY")));
        // Cursor bound with CSCN > startScn and fetchSize set.
        verify(fx.cursor).setLong(1, 500L);
        verify(fx.cursor).setFetchSize(1000);

        session.close();
        verify(fx.st).execute(Mockito.contains("END_LOGMNR"));
    }

    @Test
    void theMiningSessionPinsLocaleIndependentNlsBeforeMiningStarts() throws SQLException {
        // docker 实测(OracleToOpenGaussIT.streamedTimestampReachesANonOracleTarget):SQL_REDO
        // 的字面量按"挖掘会话的 NLS"渲染。容器默认的中文 NLS 把 TIMESTAMP 渲染成
        // TO_TIMESTAMP('04-3月 -27 05.06.07.123456 上午'),openGauss 报
        // invalid input syntax for type timestamp,CDC 任务因 APPLY_STRIKE_OUT 停车 ——
        // 该项目的增量在这一列上永久断流。会话必须在 START_LOGMNR 之前把 NLS 钉成与
        // 语言/区域无关的形态,并且数值小数点也要一并钉死(否则小数会渲染成 1,5)。
        Fixture fx = new Fixture(1000L,
                new String[]{"/oracle/arch/1_1.log"}, new String[]{"/oracle/redo/current01.log"});

        LogMinerSession.openDefault(fx.conn, 500L, 1000);

        InOrder order = Mockito.inOrder(fx.st);
        order.verify(fx.st).execute("ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS'");
        order.verify(fx.st).execute(
                "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9'");
        order.verify(fx.st).execute(
                "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM'");
        order.verify(fx.st).execute("ALTER SESSION SET NLS_DATE_LANGUAGE = 'AMERICAN'");
        order.verify(fx.st).execute("ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'");
        order.verify(fx.st).execute(Mockito.contains("START_LOGMNR"));
    }

    @Test
    void openWithoutAnyLogfileFails() throws SQLException {
        // No archived logs AND no current online members → mining has nothing to read.
        Fixture fx = new Fixture(1000L, new String[0], new String[0]);
        try {
            LogMinerSession.openDefault(fx.conn, 500L, 1000);
            org.assertj.core.api.Assertions.fail("expected SQLException for empty log list");
        } catch (SQLException e) {
            assertThat(e.getMessage()).contains("ARCHIVELOG");
        }
    }

    @Test
    void nextBatchEmitsCompleteRows() throws SQLException {
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"});
        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);

        // Two complete rows in the cursor: one INSERT, one UPDATE. Both end with ';'.
        when(fx.rows.next()).thenReturn(true, true, false);
        when(fx.rows.getString(1)).thenReturn("100", "101");
        when(fx.rows.getLong(2)).thenReturn(501L, 502L);
        when(fx.rows.getString(3)).thenReturn("tx1", "tx2");
        when(fx.rows.getString(4)).thenReturn("INSERT", "UPDATE");
        when(fx.rows.getString(5)).thenReturn("SRC", "SRC");
        when(fx.rows.getString(6)).thenReturn("T", "T");
        when(fx.rows.getString(7))
                .thenReturn("insert into \"SRC\".\"T\"(\"ID\") values ('1');",
                        "update \"SRC\".\"T\" set \"V\" = 'x' where \"ID\" = '1';");
        when(fx.rows.getTimestamp(8)).thenReturn(new Timestamp(1_000L), new Timestamp(2_000L));

        List<LogMinerSession.Row> batch = session.nextBatch();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).operation).isEqualTo("INSERT");
        assertThat(batch.get(0).csn).isEqualTo(501L);
        assertThat(batch.get(1).operation).isEqualTo("UPDATE");
        assertThat(batch.get(1).csn).isEqualTo(502L);
        session.close();
    }

    @Test
    void nextBatchMergesContinuationRowsSharingRsId() throws SQLException {
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"});
        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);

        // Same RS_ID on three rows; only the third ends with ';'.
        when(fx.rows.next()).thenReturn(true, true, true, true, false);
        // Row A (continuation 1)
        when(fx.rows.getString(1)).thenReturn("77", "77", "77", "78");
        when(fx.rows.getLong(2)).thenReturn(510L, 510L, 510L, 511L);
        when(fx.rows.getString(3)).thenReturn("txA", "txA", "txA", "txB");
        when(fx.rows.getString(4)).thenReturn("INSERT", "INSERT", "INSERT", "DELETE");
        when(fx.rows.getString(5)).thenReturn("SRC", "SRC", "SRC", "SRC");
        when(fx.rows.getString(6)).thenReturn("BIG", "BIG", "BIG", "T");
        when(fx.rows.getString(7))
                .thenReturn("insert into \"SRC\".\"BIG\"(\"ID\",\"V\")",
                        " values ('1', 'a very long value",
                        " that continues here');",
                        "delete from \"SRC\".\"T\" where \"ID\" = '9';");
        when(fx.rows.getTimestamp(8)).thenReturn(new Timestamp(0L));

        List<LogMinerSession.Row> batch = session.nextBatch();
        assertThat(batch).hasSize(2);
        // First row: all three fragments concatenated.
        LogMinerSession.Row big = batch.get(0);
        assertThat(big.rsId).isEqualTo("77");
        assertThat(big.sqlRedo).contains("insert into");
        assertThat(big.sqlRedo).contains("values");
        assertThat(big.sqlRedo).contains("continues here");
        assertThat(big.sqlRedo.trim().endsWith(";")).isTrue();
        // Second row arrives immediately (RS_ID 78 already terminated).
        assertThat(batch.get(1).rsId).isEqualTo("78");
        assertThat(batch.get(1).operation).isEqualTo("DELETE");
        session.close();
    }

    @Test
    void nextBatchStopsAtTheRowCapAndResumesWithoutLosingRows() throws SQLException {
        // A large mining window (the consumer was down for hours) must not be materialised
        // in one go: nextBatch stops at the cap and the cursor stays open, so the next call
        // picks up exactly where the previous one stopped. The consumer loops until the
        // list is empty, so an early stop is transparent.
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"});
        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);

        when(fx.rows.next()).thenReturn(true, true, true, true, true, false);
        when(fx.rows.getString(1)).thenReturn("100", "101", "102", "103", "104");
        when(fx.rows.getLong(2)).thenReturn(501L, 502L, 503L, 504L, 505L);
        when(fx.rows.getString(3)).thenReturn("tx", "tx", "tx", "tx", "tx");
        when(fx.rows.getString(4)).thenReturn("INSERT", "INSERT", "INSERT", "INSERT", "INSERT");
        when(fx.rows.getString(5)).thenReturn("SRC", "SRC", "SRC", "SRC", "SRC");
        when(fx.rows.getString(6)).thenReturn("T", "T", "T", "T", "T");
        when(fx.rows.getString(7))
                .thenReturn("insert into \"SRC\".\"T\"(\"ID\") values ('1');")
                .thenReturn("insert into \"SRC\".\"T\"(\"ID\") values ('2');")
                .thenReturn("insert into \"SRC\".\"T\"(\"ID\") values ('3');")
                .thenReturn("insert into \"SRC\".\"T\"(\"ID\") values ('4');")
                .thenReturn("insert into \"SRC\".\"T\"(\"ID\") values ('5');");
        when(fx.rows.getTimestamp(8)).thenReturn(new Timestamp(1_000L));

        assertThat(session.nextBatch(2)).extracting(row -> row.rsId)
                .containsExactly("100", "101");
        assertThat(session.nextBatch(2)).extracting(row -> row.rsId)
                .containsExactly("102", "103");
        assertThat(session.nextBatch(2)).extracting(row -> row.rsId)
                .containsExactly("104");
        // Still a non-empty batch would have meant "more to read"; empty is the drained
        // signal the consumer terminates on, so the cap must never fake it.
        assertThat(session.nextBatch(2)).isEmpty();
        session.close();
    }

    @Test
    void closeIsQuietWhenEndLogmnrFails() throws SQLException {
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"});
        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);
        when(fx.st.execute(Mockito.contains("END_LOGMNR")))
                .thenThrow(new SQLException("session already ended"));

        // Must not throw.
        session.close();
        session.close();   // idempotent

        verify(fx.st, times(1)).execute(Mockito.contains("END_LOGMNR"));
    }

    @Test
    void closeWarnsWhenUnterminatedFragmentsRemain() throws SQLException {
        // P1-8: a fragment that never reaches its ';' terminator (statement split across
        // RS_ID rows, session killed / high-water reached mid-statement) stays in the
        // pending map. close() currently drops it silently → the change is lost with no
        // breadcrumb. It must WARN naming the stranded RS_ID(s).
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"});
        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);

        when(fx.rows.next()).thenReturn(true, false);
        when(fx.rows.getString(1)).thenReturn("55");
        when(fx.rows.getLong(2)).thenReturn(520L);
        when(fx.rows.getString(3)).thenReturn("txA");
        when(fx.rows.getString(4)).thenReturn("INSERT");
        when(fx.rows.getString(5)).thenReturn("SRC");
        when(fx.rows.getString(6)).thenReturn("BIG");
        // No trailing ';' — accumulate() keeps this in pending.
        when(fx.rows.getString(7))
                .thenReturn("insert into \"SRC\".\"BIG\"(\"ID\",\"V\") values ('1', 'unterminated");
        when(fx.rows.getTimestamp(8)).thenReturn(new Timestamp(0L));

        List<LogMinerSession.Row> batch = session.nextBatch();
        assertThat(batch).isEmpty();   // fragment never completed

        ListAppender<ILoggingEvent> appender = LogCapture.attach(LogMinerSession.class);
        try {
            session.close();
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("55"));
        } finally {
            LogCapture.detach(LogMinerSession.class, appender);
        }
    }

    @Test
    void closeIsQuietWhenNoPendingFragments() throws SQLException {
        // Clean drain → close() must not manufacture a spurious WARN.
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"});
        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);
        when(fx.rows.next()).thenReturn(false);
        session.nextBatch();

        ListAppender<ILoggingEvent> appender = LogCapture.attach(LogMinerSession.class);
        try {
            session.close();
            assertThat(appender.list)
                    .noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            LogCapture.detach(LogMinerSession.class, appender);
        }
    }

    @Test
    void pre12cConnectionsSelectAConstantContainerId() throws SQLException {
        // Oracle 11g's V$LOGMNR_CONTENTS has no SRC_CON_ID (11.2.0.2 实测: ORA-00904), and
        // LogMinerSession is the only mining entry point every 11g project runs. The query
        // must select a constant there so the result shape (and the CDB filter's no-op on
        // ownConId=0) stays identical; otherwise the first read after START_LOGMNR fails.
        Fixture fx = new Fixture(1000L,
                new String[]{"/arch/1.log"}, new String[]{"/redo/cur.log"}, 11);
        ArgumentCaptor<String> prepared = ArgumentCaptor.forClass(String.class);
        when(fx.conn.prepareStatement(prepared.capture())).thenAnswer(invocation ->
                LogMinerSession.Q_ARCHIVED_LOGS_SINCE.equals(invocation.getArgument(0))
                        ? fx.archivePs : fx.cursor);

        LogMinerSession session = LogMinerSession.openDefault(fx.conn, 500L, 1000);

        assertThat(prepared.getAllValues())
                .as("11g must not reference the 12c-only SRC_CON_ID column")
                .anyMatch(sql -> sql.contains("V$LOGMNR_CONTENTS")
                        && sql.contains("0 AS SRC_CON_ID"));
        session.close();
    }

    @Test
    void contentsQuerySelectsCscnNotNonexistentCsnColumn() {
        // V$LOGMNR_CONTENTS 只有 SCN 与 CSCN 列,没有 CSN(spike 实测 ORA-00904)。
        // 全部 JDBC 走 mock,列名笔误在单测里静默通过、上真库即全灭 —— 用 SQL 文本断言钉死。
        assertThat(LogMinerSession.Q_CONTENTS)
                .contains("RS_ID, CSCN,")
                .doesNotContain("RS_ID, CSN,");
    }
}
