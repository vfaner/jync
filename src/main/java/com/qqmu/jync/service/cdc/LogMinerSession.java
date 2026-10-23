package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * One LogMiner mining session: the ADD_LOGFILE → START_LOGMNR → V$LOGMNR_CONTENTS cursor
 * bundle (v1.6.0).
 *
 * <p>Open sequence (pinned from the Task 1 spike on gvenzl/oracle-free:23):
 * <ol>
 *   <li>read the logfile list covering {@code (startScn, current_scn]} — archived logs from
 *       V$ARCHIVED_LOG (DEST_ID=1, NEXT_CHANGE# &gt; startScn) plus the CURRENT online
 *       members from V$LOGFILE/V$LOG, one file per thread+sequence (see the dedupe note in
 *       {@link #openDefault});</li>
 *   <li>ADD_LOGFILE each path with DBMS_LOGMNR.NEW on the first, ADD on the rest;</li>
 *   <li>START_LOGMNR with START_SCN, DICT_FROM_ONLINE_CATALOG, COMMITTED_DATA_ONLY;</li>
 *   <li>stream V$LOGMNR_CONTENTS filtered by {@code CSCN > ?} and row-level OPERATION.</li>
 * </ol>
 *
 * <p><b>RS_ID continuation merge</b> (Review Focus 4): SQL_REDO that outgrows LogMiner's
 * per-row buffer is split into multiple V$LOGMNR_CONTENTS rows sharing one RS_ID; only the
 * final fragment ends with ';'. {@link #nextBatch} accumulates them until the terminator and
 * emits a single {@link Row} per logical statement.
 *
 * <p>The session does NOT filter by source container: on a CDB the consumer owns that
 * predicate (SRC_CON_ID = the PDB's CON_ID); on a non-CDB every row is in scope. Oracle
 * 11g has no SRC_CON_ID column, so the cursor selects a constant 0 there instead.
 */
@Slf4j
public class LogMinerSession implements AutoCloseable {

    static final String Q_CURRENT_SCN = "SELECT CURRENT_SCN FROM V$DATABASE";
    static final String Q_CURRENT_REDO_MEMBERS =
            "SELECT F.GROUP#, F.MEMBER, L.THREAD#, L.SEQUENCE# FROM V$LOGFILE F"
                    + " JOIN V$LOG L ON L.GROUP# = F.GROUP#"
                    + " WHERE L.STATUS = 'CURRENT' ORDER BY F.GROUP#, F.MEMBER";
    static final String Q_ARCHIVED_LOGS_SINCE =
            "SELECT NAME, THREAD#, SEQUENCE# FROM V$ARCHIVED_LOG WHERE DEST_ID = 1"
                    + " AND NEXT_CHANGE# > ? AND NAME IS NOT NULL ORDER BY FIRST_CHANGE#";

    private static final String Q_CONTENTS_HEAD =
            // CSCN = commit SCN (populated under COMMITTED_DATA_ONLY); V$LOGMNR_CONTENTS
            // has NO "CSN" column — selecting it dies with ORA-00904 on every real DB.
            "SELECT RS_ID, CSCN, XID, OPERATION, SEG_OWNER, TABLE_NAME, SQL_REDO, TIMESTAMP, ";
    private static final String Q_CONTENTS_TAIL =
            " FROM V$LOGMNR_CONTENTS"
                    + " WHERE CSCN > ? AND OPERATION IN ('INSERT','UPDATE','DELETE','DDL')"
                    + " ORDER BY CSCN, RS_ID";

    /** 12c+: SRC_CON_ID exists and carries the row's origin container on a CDB. */
    static final String Q_CONTENTS = Q_CONTENTS_HEAD + "SRC_CON_ID" + Q_CONTENTS_TAIL;

    /**
     * Oracle 11g: V$LOGMNR_CONTENTS has no SRC_CON_ID (verified on 11.2.0.2 — selecting it
     * raises ORA-00904). A literal 0 keeps the result shape and makes the consumer's
     * container filter a no-op, exactly as it is on a non-CDB.
     */
    static final String Q_CONTENTS_PRE_12C = Q_CONTENTS_HEAD + "0 AS SRC_CON_ID" + Q_CONTENTS_TAIL;

    private final Connection source;
    private final long startScn;
    private final long currentScnAtOpen;
    private final PreparedStatement cursor;
    private final ResultSet rows;
    /** RS_ID fragments not yet terminated by ';' — RS_ID → accumulated SQL_REDO. */
    private final Map<String, StringBuilder> pending = new LinkedHashMap<>();
    private boolean closed;

    LogMinerSession(Connection source, long startScn, long currentScnAtOpen,
                    PreparedStatement cursor, ResultSet rows) {
        this.source = source;
        this.startScn = startScn;
        this.currentScnAtOpen = currentScnAtOpen;
        this.cursor = cursor;
        this.rows = rows;
    }

    /** SCN this session anchored to — the consumer resumes the next cycle from here. */
    public long startScn() {
        return startScn;
    }

    /** Upper SCN bound at open (V$DATABASE.CURRENT_SCN at session start). */
    public long highWaterScn() {
        return currentScnAtOpen;
    }

    /**
     * Hard ceiling on rows materialised by one {@link #nextBatch()} call. A consumer that
     * was down for hours re-mines a backlog spanning the whole window; draining it into one
     * list would hold every SQL_REDO string of that window in the heap before a single row
     * is applied. The cap only bounds the in-memory burst — the cursor stays open, so the
     * next call continues where this one stopped.
     */
    static final int MAX_BATCH_ROWS = 10_000;

    /**
     * Next batch of fully-formed rows, continuation fragments merged. An empty list means
     * the cursor drained: the consumer closes the session and polls before reopening.
     * Never returns more than {@link #MAX_BATCH_ROWS} rows.
     */
    public List<Row> nextBatch() throws SQLException {
        return nextBatch(MAX_BATCH_ROWS);
    }

    /**
     * Cap-testable seam for {@link #nextBatch()}. Continuation fragments do not count
     * toward {@code maxRows}: a fragment yields no Row, so the loop keeps reading until it
     * either completes a statement or the cursor drains — a fragment is never left half-read
     * across calls. An empty result still means "cursor drained", because the loop only stops
     * early after emitting a row.
     */
    List<Row> nextBatch(int maxRows) throws SQLException {
        List<Row> out = new ArrayList<>();
        while (out.size() < maxRows && rows.next()) {
            String rsId = rows.getString(1);
            long csn = rows.getLong(2);
            String operation = rows.getString(4);
            String segOwner = rows.getString(5);
            String tableName = rows.getString(6);
            String sqlRedo = rows.getString(7);
            Timestamp rowTime = rows.getTimestamp(8);
            // SRC_CON_ID: only meaningful on a CDB (12c+); NULL/0 on non-CDB. The consumer
            // uses it to filter rows belonging to other PDBs when mining from CDB$ROOT.
            int srcConId = rows.getInt(9);
            if (sqlRedo == null) {
                continue;
            }
            String accumulated = accumulate(rsId, sqlRedo);
            if (accumulated == null) {
                continue;                                   // fragment only, wait for more
            }
            out.add(new Row(rsId, csn, operation, segOwner, tableName,
                    accumulated, rowTime, srcConId));
        }
        // B1 follow-up landed: the former batchCap() "safety valve" returned
        // Integer.MAX_VALUE, so its guard never fired. The loop condition above is the real
        // cap (MAX_BATCH_ROWS). LogMinerCdcConsumer loops nextBatch until it returns empty,
        // so stopping early is transparent to it and its per-transaction buffers carry over.
        return out;
    }

    /**
     * Append {@code fragment} to the RS_ID buffer and return the complete SQL_REDO once the
     * logical statement terminates (';' as the last non-whitespace char). Returns null while
     * more fragments are expected.
     */
    private String accumulate(String rsId, String fragment) {
        StringBuilder buf = pending.get(rsId);
        if (buf == null) {
            buf = new StringBuilder();
            pending.put(rsId, buf);
        }
        if (buf.length() > 0) {
            buf.append('\n');
        }
        buf.append(fragment);
        String text = buf.toString();
        String trimmed = text.trim();
        if (trimmed.endsWith(";")) {
            pending.remove(rsId);
            return text;
        }
        return null;
    }

    /** END_LOGMNR quietly; the cursor and result set are closed with the source pooled conn. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // P1-8: SQL_REDO fragments that never reached their ';' terminator are dropped
        // here. Under COMMITTED_DATA_ONLY with a complete log range this is close to
        // impossible, so the fix is a diagnostic breadcrumb, not a recovery mechanism —
        // and the text must not promise recovery: later commits in the same session can
        // carry the stored position past the dropped CSCN, making it unrecoverable.
        if (!pending.isEmpty()) {
            log.warn("Closing LogMiner session (startScn {}) with {} unterminated"
                    + " SQL_REDO fragment buffer(s) — RS_IDs {} dropped incomplete;"
                    + " these changes were NOT applied and may be unrecoverable from"
                    + " the stored position — investigate those RS_IDs in the redo"
                    + " log", startScn, pending.size(), pending.keySet());
            pending.clear();
        }
        try (Statement st = source.createStatement()) {
            st.execute("BEGIN DBMS_LOGMNR.END_LOGMNR; END;");
        } catch (SQLException e) {
            // Ending an already-ended or server-dropped session is benign; the connection
            // itself returns to the pool via the consumer's finally block.
            log.debug("END_LOGMNR for SCN {} session quiet-failed: {}", startScn, e.getMessage());
        }
        try {
            rows.close();
        } catch (SQLException e) {
            log.debug("Closing V$LOGMNR_CONTENTS cursor quiet-failed: {}", e.getMessage());
        }
        try {
            cursor.close();
        } catch (SQLException e) {
            log.debug("Closing mining prepared statement quiet-failed: {}", e.getMessage());
        }
    }

    /** One fully-formed change row from V$LOGMNR_CONTENTS (continuation fragments merged). */
    public static final class Row {
        public final String rsId;
        public final long csn;
        public final String operation;
        public final String segOwner;
        public final String tableName;
        public final String sqlRedo;
        public final Timestamp rowTime;
        /** Container ID (CDB only); 0 on non-CDB. */
        public final int srcConId;

        public Row(String rsId, long csn, String operation,
                   String segOwner, String tableName, String sqlRedo, Timestamp rowTime,
                   int srcConId) {
            this.rsId = rsId;
            this.csn = csn;
            this.operation = operation;
            this.segOwner = segOwner;
            this.tableName = tableName;
            this.sqlRedo = sqlRedo;
            this.rowTime = rowTime;
            this.srcConId = srcConId;
        }
    }

    // —— package-private factory used by the default Spring-built session below ——

    /**
     * Default {@link LogMinerSessionFactory}: reads current_scn, the logfile list, runs
     * ADD_LOGFILE/START_LOGMNR, and returns an open cursor. Extracted as a static so tests
     * can exercise the open sequence with mocks without going through Spring.
     */
    /** The cursor query, split solely by whether the server has V$LOGMNR_CONTENTS.SRC_CON_ID. */
    static String contentsQuery(int databaseMajorVersion) {
        return databaseMajorVersion >= 12 ? Q_CONTENTS : Q_CONTENTS_PRE_12C;
    }

    static LogMinerSession openDefault(Connection source, long startScn, int batchSize)
            throws SQLException {
        int majorVersion = source.getMetaData().getDatabaseMajorVersion();
        pinLocaleIndependentNls(source);
        long currentScn;
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_CURRENT_SCN)) {
            if (!rs.next()) {
                throw new SQLException("Could not read CURRENT_SCN from V$DATABASE");
            }
            currentScn = rs.getLong(1);
        }

        // LogMiner identifies a log file by thread + sequence number, not by path, and
        // raises ORA-01289 when the same (thread, sequence) is added twice. Three shapes
        // produce that duplicate on a live database:
        //   * V$ARCHIVED_LOG also carries a row for the CURRENT group, and on a FRA
        //     database that row's NAME is the online member path — same path twice;
        //   * a multiplexed CURRENT group has several members sharing one sequence;
        //   * ALTER SYSTEM ARCHIVE LOG CURRENT archives the group without switching it, so
        //     an archived copy and the still-CURRENT online member share a sequence.
        // Keep insertion order (archived first, then redo) and add at most one file per
        // thread+sequence.
        Set<String> logfiles = new LinkedHashSet<>();
        Set<String> addedSequences = new HashSet<>();
        // Use a PreparedStatement for the SCN (numeric bind, no injection) — the string
        // .replace("?", ...) below worked, but a bind keeps the query plan cache stable.
        try (PreparedStatement ps = source.prepareStatement(Q_ARCHIVED_LOGS_SINCE)) {
            ps.setLong(1, startScn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (addedSequences.add(threadSequence(rs.getInt(2), rs.getLong(3)))) {
                        logfiles.add(rs.getString(1));
                    }
                }
            }
        }
        Set<Integer> archivedGroups = new HashSet<>();
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_CURRENT_REDO_MEMBERS)) {
            while (rs.next()) {
                int group = rs.getInt(1);
                String member = rs.getString(2);
                String sequence = threadSequence(rs.getInt(3), rs.getLong(4));
                // One member per group: Oracle needs only one copy of a multiplexed group.
                // A group whose sequence is already archived (ARCHIVE LOG CURRENT) is
                // already covered, so its online member must not be added again.
                if (archivedGroups.add(group) && addedSequences.add(sequence)) {
                    logfiles.add(member);
                }
            }
        }
        if (logfiles.isEmpty()) {
            throw new SQLException("No archived or online redo logs cover SCN " + startScn
                    + "; the source may not be in ARCHIVELOG mode or logs were purged");
        }

        try (Statement st = source.createStatement()) {
            boolean first = true;
            for (String path : logfiles) {
                String flag = first ? "DBMS_LOGMNR.NEW" : "DBMS_LOGMNR.ADDFILE";
                // H5: escape single quotes in the logfile path. Oracle-managed / Windows
                // paths can contain apostrophes (rare, but a single bad path used to
                // break the whole PL/SQL block with a syntax error).
                String safePath = path.replace("'", "''");
                st.execute("BEGIN DBMS_LOGMNR.ADD_LOGFILE(logfilename => '" + safePath + "',"
                        + " options => " + flag + "); END;");
                first = false;
            }
            // SCNs are numeric; inline substitution is injection-free (setLong would
            // also work, but BEGIN ...; blocks don't accept binds for scalar args).
            st.execute("BEGIN DBMS_LOGMNR.START_LOGMNR(startScn => " + startScn + ","
                    + " endScn => " + currentScn + ","
                    + " options => DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG"
                    + " + DBMS_LOGMNR.COMMITTED_DATA_ONLY); END;");
        }

        PreparedStatement cursor = source.prepareStatement(contentsQuery(majorVersion));
        cursor.setLong(1, startScn);
        if (batchSize > 0) {
            cursor.setFetchSize(batchSize);
        }
        ResultSet rows = cursor.executeQuery();
        return new LogMinerSession(source, startScn, currentScn, cursor, rows);
    }

    /** Identity LogMiner deduplicates log files by (thread number + log sequence). */
    private static String threadSequence(int thread, long sequence) {
        return thread + ":" + sequence;
    }

    /**
     * Pins the mining session's NLS to values that do not depend on the server's locale.
     *
     * <p>LogMiner renders {@code V$LOGMNR_CONTENTS.SQL_REDO} — every temporal and numeric
     * literal in the redo text — using the <em>mining session's</em> NLS settings, not the
     * settings of the session that wrote the change. On a Chinese-locale server that turns a
     * TIMESTAMP update into
     * {@code TO_TIMESTAMP('04-3月 -27 05.06.07.123456 上午')}, which no target outside the same
     * locale can parse: measured against openGauss 5.0 the apply fails with
     * {@code invalid input syntax for type timestamp}, and the consumer parks the project in
     * ERROR — the increment stops permanently on the first temporal column. The numeric
     * characters matter for the same reason ({@code 1,5} instead of {@code 1.5}).
     *
     * <p>The connection comes from the shared source pool, so these settings outlive the
     * mining session on that physical connection until it is borrowed again. They are
     * deliberately not restored: restoring needs the previous values from
     * {@code V$NLS_PARAMETERS} (a privilege the mining account may not have) plus a round
     * trip on the close path of every poll cycle, and nothing else in the pipeline depends
     * on session NLS — rows are read through typed JDBC getters and written through binds,
     * never through locale-formatted string literals. Revisit if a source-side query ever
     * starts comparing dates or numbers as text on the same pool.
     */
    private static void pinLocaleIndependentNls(Connection source) throws SQLException {
        List<String> settings = List.of(
                "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS'",
                "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9'",
                "ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM'",
                "ALTER SESSION SET NLS_DATE_LANGUAGE = 'AMERICAN'",
                "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'");
        try (Statement st = source.createStatement()) {
            for (String setting : settings) {
                st.execute(setting);
            }
        }
    }
}
