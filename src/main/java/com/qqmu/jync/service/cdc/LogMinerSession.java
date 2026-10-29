package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * One LogMiner mining session: the shared half of the pipeline — the
 * V$LOGMNR_CONTENTS cursor, RS_ID continuation merging, the batch cap, and END_LOGMNR on
 * close (v1.6.0; dialect seam added for DM in v1.9.0).
 *
 * <p>Everything a vendor does differently — how far the redo goes, which files can be mined,
 * ADD_LOGFILE/START_LOGMNR syntax, start-SCN comparison — lives in {@link MiningDialect}.
 * {@link #open} runs the sequence: session setup → high-water SCN → log discovery →
 * ADD_LOGFILE → START_LOGMNR → cursor. On Oracle that sequence is the one pinned in the
 * v1.6.0 spike on gvenzl/oracle-free:23 (archived files plus the CURRENT online members,
 * one file per thread+sequence, {@code CSCN > startScn}). On DM it is
 * {@link DmLogMinerDialect}'s: archived files only, {@code CSCN >= startScn}.
 *
 * <p><b>RS_ID continuation merge</b> (Review Focus 4): SQL_REDO that outgrows LogMiner's
 * per-row buffer is split into multiple V$LOGMNR_CONTENTS rows sharing one RS_ID; only the
 * final fragment ends with ';'. {@link #nextBatch} accumulates them until the terminator and
 * emits a single {@link Row} per logical statement. DM's RS_ID is not monotonic across
 * statements (docs/dm-logminer-spike.md §2.3.2), which is fine here: the key is only used
 * while a fragment is outstanding.
 *
 * <p>The session does NOT filter by source container: on a CDB the consumer owns that
 * predicate (SRC_CON_ID = the PDB's CON_ID); on a non-CDB every row is in scope. Oracle
 * 11g and every DM build have no SRC_CON_ID column, so the cursor selects a constant 0
 * there instead.
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
    /**
     * Split at the comparison so the start-SCN operator is the dialect's choice: Oracle
     * resumes from the last delivered SCN and needs ">", DM resumes from a file boundary
     * and needs ">=".
     */
    private static final String Q_CONTENTS_FROM = " FROM V$LOGMNR_CONTENTS WHERE CSCN";
    private static final String Q_CONTENTS_FILTER =
            " AND OPERATION IN ('INSERT','UPDATE','DELETE','DDL') ORDER BY CSCN, RS_ID";

    /** 12c+: SRC_CON_ID exists and carries the row's origin container on a CDB. */
    static final String Q_CONTENTS = Q_CONTENTS_HEAD + "SRC_CON_ID" + Q_CONTENTS_FROM
            + " > ?" + Q_CONTENTS_FILTER;

    /**
     * Oracle 11g: V$LOGMNR_CONTENTS has no SRC_CON_ID (verified on 11.2.0.2 — selecting it
     * raises ORA-00904). A literal 0 keeps the result shape and makes the consumer's
     * container filter a no-op, exactly as it is on a non-CDB.
     */
    static final String Q_CONTENTS_PRE_12C = Q_CONTENTS_HEAD + "0 AS SRC_CON_ID"
            + Q_CONTENTS_FROM + " > ?" + Q_CONTENTS_FILTER;

    private final Connection source;
    private final long startScn;
    private final long currentScnAtOpen;
    private final PreparedStatement cursor;
    private final ResultSet rows;
    private final MiningDialect dialect;
    /** RS_ID fragments not yet terminated by ';' — RS_ID → accumulated SQL_REDO. */
    private final Map<String, StringBuilder> pending = new LinkedHashMap<>();
    private boolean closed;

    /** Test-only shape: a hand-built session over mocked JDBC, closing through Oracle. */
    LogMinerSession(Connection source, long startScn, long currentScnAtOpen,
                    PreparedStatement cursor, ResultSet rows) {
        this(source, startScn, currentScnAtOpen, cursor, rows,
                OracleLogMinerDialect.INSTANCE);
    }

    LogMinerSession(Connection source, long startScn, long currentScnAtOpen,
                    PreparedStatement cursor, ResultSet rows, MiningDialect dialect) {
        this.source = source;
        this.startScn = startScn;
        this.currentScnAtOpen = currentScnAtOpen;
        this.cursor = cursor;
        this.rows = rows;
        this.dialect = dialect;
    }

    /**
     * A cycle with nothing to mine (DM before its first archive switch). It idles at the
     * current position instead of failing: the consumer polls again next cycle and no
     * APPLY_STRIKE accrues. {@link #nextBatch()} is empty and {@link #close()} issues no
     * commands, because no mining session was ever started.
     */
    static LogMinerSession idle(Connection source, long startScn, MiningDialect dialect) {
        return new LogMinerSession(source, startScn, startScn, null, null, dialect);
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
        if (rows == null) {
            return out;                                 // idle session: nothing was mined
        }
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
        if (cursor == null) {
            return;                                     // idle session: nothing was started
        }
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
        dialect.endMining(source);
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

    /** The cursor query, split solely by whether the server has V$LOGMNR_CONTENTS.SRC_CON_ID. */
    static String contentsQuery(int databaseMajorVersion) {
        return contentsQuery(databaseMajorVersion, false);
    }

    /**
     * The cursor query with the dialect's start-SCN comparison: {@code >} for Oracle (resume
     * from the last delivered CSCN), {@code >=} for DM (resume from a file boundary, so the
     * first commit of the next file may sit exactly on it).
     */
    static String contentsQuery(int databaseMajorVersion, boolean inclusiveStartScn) {
        String container = databaseMajorVersion >= 12 ? "SRC_CON_ID" : "0 AS SRC_CON_ID";
        return Q_CONTENTS_HEAD + container + Q_CONTENTS_FROM
                + (inclusiveStartScn ? " >= ?" : " > ?") + Q_CONTENTS_FILTER;
    }

    /**
     * Oracle's entry point, kept so every existing caller (and every mocked open-sequence
     * test) goes through the same dialect the product has always used.
     */
    static LogMinerSession openDefault(Connection source, long startScn, int batchSize)
            throws SQLException {
        return open(source, startScn, batchSize, OracleLogMinerDialect.INSTANCE);
    }

    /**
     * Opens one mining cycle through {@code dialect}: session setup, high-water SCN, log
     * discovery, ADD_LOGFILE/START_LOGMNR, then the V$LOGMNR_CONTENTS cursor.
     *
     * <p>A dialect that {@link MiningDialect#waitsForArchives() waits for archives} and finds
     * none returns an {@link #idle idle} session instead of throwing: on DM, "the redo has
     * not been switched yet" is a normal poll, not a misconfiguration.
     */
    static LogMinerSession open(Connection source, long startScn, int batchSize,
                                MiningDialect dialect) throws SQLException {
        dialect.prepareSession(source);
        long highWaterScn = dialect.highWaterScn(source);
        List<String> logfiles = dialect.logfilesCovering(source, startScn);
        if (logfiles.isEmpty()) {
            if (dialect.waitsForArchives()) {
                return idle(source, startScn, dialect);
            }
            throw new SQLException("No archived or online redo logs cover SCN " + startScn
                    + "; the source may not be in ARCHIVELOG mode or logs were purged");
        }

        dialect.startMining(source, logfiles, startScn, highWaterScn);

        // From here on the mining session is RUNNING. Anything that fails before the caller
        // gets the LogMinerSession object would otherwise leave it running on a connection
        // that returns to the pool (the consumer only assigns currentSession after open()
        // returns, so its finally-block close() never sees this one) — and a pooled physical
        // session still holding a LogMiner context is exactly what docs/dm-logminer-spike.md
        // forbids: ADD_LOGFILE → START_LOGMNR → cursor → END_LOGMNR on ONE connection,
        // END_LOGMNR included on the exception path.
        PreparedStatement cursor = null;
        try {
            cursor = source.prepareStatement(dialect.contentsQuery(source));
            cursor.setLong(1, startScn);
            if (batchSize > 0) {
                cursor.setFetchSize(batchSize);
            }
            ResultSet rows = cursor.executeQuery();
            return new LogMinerSession(source, startScn, highWaterScn, cursor, rows, dialect);
        } catch (SQLException | RuntimeException e) {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (SQLException ignored) {
                    // the connection is either still usable or about to be discarded
                }
            }
            dialect.endMining(source);
            throw e;
        }
    }
}
