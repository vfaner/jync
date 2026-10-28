package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * Oracle's LogMiner dialect: archived logs plus the CURRENT online members, bounded by
 * {@code V$DATABASE.CURRENT_SCN}.
 *
 * <p>The open sequence here is the one measured in v1.6.0 on gvenzl/oracle-free:23 —
 * read the high-water SCN, collect one file per (thread, sequence), ADD_LOGFILE each with
 * NEW on the first, START_LOGMNR with START_SCN/END_SCN and the dictionary and
 * committed-only flags.
 */
@Slf4j
public final class OracleLogMinerDialect implements MiningDialect {

    public static final OracleLogMinerDialect INSTANCE = new OracleLogMinerDialect();

    /** Capability probe: DM's V$DATABASE has no CURRENT_SCN column. */
    static final String Q_VERIFY = "SELECT CURRENT_SCN FROM V$DATABASE";

    private OracleLogMinerDialect() {
    }

    @Override
    public String name() {
        return "oracle";
    }

    @Override
    public void verify(Connection source) throws SQLException {
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_VERIFY)) {
            rs.next();
        }
    }

    @Override
    public void prepareSession(Connection source) throws SQLException {
        pinLocaleIndependentNls(source);
    }

    @Override
    public long highWaterScn(Connection source) throws SQLException {
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(Q_VERIFY)) {
            if (!rs.next()) {
                throw new SQLException("Could not read CURRENT_SCN from V$DATABASE");
            }
            return rs.getLong(1);
        }
    }

    @Override
    public List<String> logfilesCovering(Connection source, long startScn) throws SQLException {
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
        // A numeric bind, not string substitution: no injection surface and a stable plan.
        try (PreparedStatement ps = source.prepareStatement(LogMinerSession.Q_ARCHIVED_LOGS_SINCE)) {
            ps.setLong(1, startScn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (addedSequences.add(MiningDialect.threadSequence(rs.getInt(2), rs.getLong(3)))) {
                        logfiles.add(rs.getString(1));
                    }
                }
            }
        }
        Set<Integer> archivedGroups = new HashSet<>();
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery(LogMinerSession.Q_CURRENT_REDO_MEMBERS)) {
            while (rs.next()) {
                int group = rs.getInt(1);
                String member = rs.getString(2);
                String sequence = MiningDialect.threadSequence(rs.getInt(3), rs.getLong(4));
                // One member per group: Oracle needs only one copy of a multiplexed group.
                // A group whose sequence is already archived (ARCHIVE LOG CURRENT) is
                // already covered, so its online member must not be added again.
                if (archivedGroups.add(group) && addedSequences.add(sequence)) {
                    logfiles.add(member);
                }
            }
        }
        return new ArrayList<>(logfiles);
    }

    @Override
    public void startMining(Connection source, List<String> logfiles, long startScn,
                            long highWaterScn) throws SQLException {
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
                    + " endScn => " + highWaterScn + ","
                    + " options => DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG"
                    + " + DBMS_LOGMNR.COMMITTED_DATA_ONLY); END;");
        }
    }

    @Override
    public void endMining(Connection source) {
        try (Statement st = source.createStatement()) {
            st.execute("BEGIN DBMS_LOGMNR.END_LOGMNR; END;");
        } catch (SQLException e) {
            // Ending an already-ended or server-dropped session is benign; the connection
            // itself returns to the pool via the consumer's finally block.
            log.debug("END_LOGMNR quiet-failed: {}", e.getMessage());
        }
    }

    @Override
    public String contentsQuery(Connection source) throws SQLException {
        return LogMinerSession.contentsQuery(source.getMetaData().getDatabaseMajorVersion());
    }

    @Override
    public boolean waitsForArchives() {
        return false;
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
