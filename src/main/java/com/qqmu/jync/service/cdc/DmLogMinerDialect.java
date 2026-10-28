package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DM8's SYS.DBMS_LOGMNR dialect. Every command below was measured against DM8
 * ({@code DB Version: 0x7000c}); see docs/dm-logminer-spike.md for the transcripts.
 *
 * <p>The four differences that make this a separate implementation rather than a
 * parameterised Oracle one:
 * <ul>
 *   <li>only archived files can be mined — adding a member from {@code V$RLOGFILE} fails
 *       with {@code [-4546] File open failure}, so CDC latency equals the archive-switch
 *       interval;</li>
 *   <li>{@code V$DATABASE} has no {@code CURRENT_SCN}; the newest archived file's
 *       {@code NEXT_CHANGE#} is the ceiling;</li>
 *   <li>{@code ADD_LOGFILE} is positional and its option {@code 1} means add — Oracle's
 *       {@code DBMS_LOGMNR.NEW}/{@code ADDFILE} constants do not exist and the block form
 *       raises {@code [-2007] Syntax error};</li>
 *   <li>there are no NLS session parameters to pin.</li>
 * </ul>
 */
public final class DmLogMinerDialect implements MiningDialect {

    public static final DmLogMinerDialect INSTANCE = new DmLogMinerDialect();

    /**
     * Capability probe: {@code V$RLOGFILE} is DM-only (a DM instance also lacks
     * {@code V$DATABASE.CURRENT_SCN}, which is why the Oracle probe fails first).
     */
    static final String Q_VERIFY = "SELECT COUNT(*) FROM V$RLOGFILE";

    /** First SCN that is not archived yet — an exclusive upper bound for one cycle. */
    static final String Q_HIGH_WATER = "SELECT MAX(NEXT_CHANGE#) FROM V$ARCHIVED_LOG";

    /**
     * Archived files carrying changes above {@code startScn}. Deliberately without Oracle's
     * {@code DEST_ID = 1}: DM leaves {@code DEST_ID} NULL on every row, so that predicate
     * would match nothing, forever.
     */
    static final String Q_ARCHIVED_LOGS_SINCE =
            "SELECT NAME, THREAD#, SEQUENCE# FROM V$ARCHIVED_LOG"
                    + " WHERE NEXT_CHANGE# > ? AND NAME IS NOT NULL ORDER BY FIRST_CHANGE#";

    /** {@code MAX(NEXT_CHANGE#)} is NULL — nothing is archived, so there is no ceiling yet. */
    static final long NO_HIGH_WATER = -1L;

    private static final int OPTION_ADD_LOGFILE = 1;

    private DmLogMinerDialect() {
    }

    @Override
    public String name() {
        return "dm";
    }

    @Override
    public void verify(Connection source) throws SQLException {
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_VERIFY)) {
            if (!rs.next()) {
                throw new SQLException("Probe returned no row: " + Q_VERIFY);
            }
        } catch (SQLException e) {
            throw new SQLException("DM probe failed (" + Q_VERIFY + "): " + e.getMessage(), e);
        }
    }

    @Override
    public void prepareSession(Connection source) {
        // DM defines no NLS_DATE_FORMAT / NLS_NUMERIC_CHARACTERS session parameters, and
        // its SQL_REDO renders values independently of the session locale (the spike's
        // DATE'…' / TIMESTAMP'…' forms). Nothing to pin.
    }

    @Override
    public long highWaterScn(Connection source) throws SQLException {
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_HIGH_WATER)) {
            if (!rs.next()) {
                return NO_HIGH_WATER;
            }
            long nextChange = rs.getLong(1);
            return rs.wasNull() ? NO_HIGH_WATER : nextChange;
        }
    }

    @Override
    public List<String> logfilesCovering(Connection source, long startScn) throws SQLException {
        List<String> logfiles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (PreparedStatement ps = source.prepareStatement(Q_ARCHIVED_LOGS_SINCE)) {
            ps.setLong(1, startScn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (seen.add(MiningDialect.threadSequence(rs.getInt(2), rs.getLong(3)))) {
                        logfiles.add(rs.getString(1));
                    }
                }
            }
        }
        return logfiles;
    }

    @Override
    public void startMining(Connection source, List<String> logfiles, long startScn,
                            long highWaterScn) throws SQLException {
        if (logfiles.isEmpty()) {
            return;
        }
        try (Statement st = source.createStatement()) {
            for (String path : logfiles) {
                st.execute("CALL DBMS_LOGMNR.ADD_LOGFILE('" + path.replace("'", "''") + "', "
                        + OPTION_ADD_LOGFILE + ")");
            }
            // Named arguments are supported on DM8 (measured). Both flags are load-bearing:
            // without DICT_FROM_ONLINE_CATALOG the redo columns come out as "COL 1"…
            // (measured), and without COMMITTED_DATA_ONLY the view carries uncommitted and
            // rolled-back changes.
            st.execute("CALL DBMS_LOGMNR.START_LOGMNR(startScn => " + startScn
                    + ", endScn => " + highWaterScn
                    + ", options => DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG"
                    + " + DBMS_LOGMNR.COMMITTED_DATA_ONLY)");
        }
    }

    @Override
    public void endMining(Connection source) {
        try (Statement st = source.createStatement()) {
            st.execute("CALL DBMS_LOGMNR.END_LOGMNR()");
        } catch (SQLException e) {
            // Ending an already-ended or server-dropped session is benign.
            org.slf4j.LoggerFactory.getLogger(DmLogMinerDialect.class)
                    .debug("END_LOGMNR quiet-failed: {}", e.getMessage());
        }
    }

    @Override
    public String contentsQuery(Connection source) {
        // Inclusive start: DM's resume SCN is a file boundary (MAX(NEXT_CHANGE#)), so the
        // next file's first commit can sit exactly on it. Oracle's "CSCN > ?" would drop
        // that row. Version 0 requests the pre-12c shape — no DM build has SRC_CON_ID.
        return LogMinerSession.contentsQuery(0, true);
    }

    @Override
    public boolean waitsForArchives() {
        return true;
    }
}
