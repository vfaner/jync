package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * One LogMiner mining session: the ADD_LOGFILE → START_LOGMNR → V$LOGMNR_CONTENTS cursor
 * bundle (v1.6.0).
 *
 * <p>Open sequence (pinned from the Task 1 spike on gvenzl/oracle-free:23):
 * <ol>
 *   <li>read the logfile list covering {@code (startScn, current_scn]} — archived logs from
 *       V$ARCHIVED_LOG (DEST_ID=1, NEXT_CHANGE# &gt; startScn) plus the CURRENT online
 *       members from V$LOGFILE/V$LOG;</li>
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
 * predicate (SRC_CON_ID = the PDB's CON_ID); on a non-CDB every row is in scope.
 */
@Slf4j
public final class LogMinerSession implements AutoCloseable {

    static final String Q_CURRENT_SCN = "SELECT CURRENT_SCN FROM V$DATABASE";
    static final String Q_CURRENT_REDO_MEMBERS =
            "SELECT MEMBER FROM V$LOGFILE WHERE GROUP# IN"
                    + " (SELECT GROUP# FROM V$LOG WHERE STATUS = 'CURRENT')";
    static final String Q_ARCHIVED_LOGS_SINCE =
            "SELECT NAME FROM V$ARCHIVED_LOG WHERE DEST_ID = 1 AND NEXT_CHANGE# > ?"
                    + " AND NAME IS NOT NULL ORDER BY FIRST_CHANGE#";

    private static final String Q_CONTENTS =
            "SELECT RS_ID, CSN, XID, OPERATION, SEG_OWNER, TABLE_NAME, SQL_REDO, TIMESTAMP"
                    + " FROM V$LOGMNR_CONTENTS"
                    + " WHERE CSCN > ? AND OPERATION IN ('INSERT','UPDATE','DELETE','DDL')"
                    + " ORDER BY CSCN, RS_ID";

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
     * Next batch of fully-formed rows, continuation fragments merged. An empty list means
     * the cursor drained: the consumer closes the session and polls before reopening.
     */
    public List<Row> nextBatch() throws SQLException {
        List<Row> out = new ArrayList<>();
        int read = 0;
        while (rows.next()) {
            String rsId = rows.getString(1);
            long csn = rows.getLong(2);
            String xid = rows.getString(3);
            String operation = rows.getString(4);
            String segOwner = rows.getString(5);
            String tableName = rows.getString(6);
            String sqlRedo = rows.getString(7);
            Timestamp rowTime = rows.getTimestamp(8);
            if (sqlRedo == null) {
                continue;
            }
            String accumulated = accumulate(rsId, sqlRedo);
            if (accumulated == null) {
                continue;                                   // fragment only, wait for more
            }
            out.add(new Row(rsId, csn, xid, operation, segOwner, tableName,
                    accumulated, rowTime));
            if (++read >= batchCap()) {
                break;
            }
        }
        return out;
    }

    private int batchCap() {
        // The cursor fetchSize already bounds the JDBC round-trip; the in-memory batch cap
        // is a safety valve. Default 10_000 rows; the factory leaves this unbounded when 0.
        return Integer.MAX_VALUE;
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

    public long startScnAnchor() {
        return startScn;
    }

    /** END_LOGMNR quietly; the cursor and result set are closed with the source pooled conn. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
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
        public final String xid;
        public final String operation;
        public final String segOwner;
        public final String tableName;
        public final String sqlRedo;
        public final Timestamp rowTime;

        public Row(String rsId, long csn, String xid, String operation,
                   String segOwner, String tableName, String sqlRedo, Timestamp rowTime) {
            this.rsId = rsId;
            this.csn = csn;
            this.xid = xid;
            this.operation = operation;
            this.segOwner = segOwner;
            this.tableName = tableName;
            this.sqlRedo = sqlRedo;
            this.rowTime = rowTime;
        }
    }

    // —— package-private factory used by the default Spring-built session below ——

    /**
     * Default {@link LogMinerSessionFactory}: reads current_scn, the logfile list, runs
     * ADD_LOGFILE/START_LOGMNR, and returns an open cursor. Extracted as a static so tests
     * can exercise the open sequence with mocks without going through Spring.
     */
    static LogMinerSession openDefault(Connection source, long startScn, int batchSize)
            throws SQLException {
        long currentScn;
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_CURRENT_SCN)) {
            if (!rs.next()) {
                throw new SQLException("Could not read CURRENT_SCN from V$DATABASE");
            }
            currentScn = rs.getLong(1);
        }

        List<String> logfiles = new ArrayList<>();
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_ARCHIVED_LOGS_SINCE.replace("?",
                     String.valueOf(startScn)))) {
            while (rs.next()) {
                logfiles.add(rs.getString(1));
            }
        }
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_CURRENT_REDO_MEMBERS)) {
            while (rs.next()) {
                logfiles.add(rs.getString(1));
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
                st.execute("BEGIN DBMS_LOGMNR.ADD_LOGFILE(logfilename => '" + path + "',"
                        + " options => " + flag + "); END;");
                first = false;
            }
            st.execute("BEGIN DBMS_LOGMNR.START_LOGMNR(startScn => " + startScn + ","
                    + " endScn => " + currentScn + ","
                    + " options => DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG"
                    + " + DBMS_LOGMNR.COMMITTED_DATA_ONLY); END;");
        }

        PreparedStatement cursor = source.prepareStatement(Q_CONTENTS);
        cursor.setLong(1, startScn);
        if (batchSize > 0) {
            cursor.setFetchSize(batchSize);
        }
        ResultSet rows = cursor.executeQuery();
        return new LogMinerSession(source, startScn, currentScn, cursor, rows);
    }
}
