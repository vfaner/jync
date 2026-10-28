package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * v1.6.0 Oracle LogMiner readiness probe — the Oracle twin of {@link BinlogCdcProbe}.
 *
 * <p>Itemized, never throws: a merely-unready source degrades to failed checks with the
 * remediation text a DBA can act on, not a stack trace. Check order is the report order
 * the connection-test page renders.
 *
 * <p>Scope: the products whose mining behaviour has been measured — literal Oracle (11g and
 * 12c+ are version-adaptive on the mining-role check ⑥) and, since v1.9.0, DM8. Which one
 * runs is decided by {@link MiningDialects#detect(Connection)}, the same capability probe
 * the engine itself uses, so the readiness report cannot disagree with what mining will
 * actually do. Products that merely <em>answer</em> like Oracle (崖山, OceanBase Oracle
 * mode) are refused by {@link CdcProbeResult#ORACLE_SOURCE}, and that refusal is the whole
 * report: YashanDB 23.4.7.100 was measured on 2026-10-29 and has no {@code DBMS_LOGMNR} at
 * all, so the following checks would ask it questions it cannot answer.
 */
@Component
@Slf4j
public class LogMinerCdcProbe {

    /**
     * @param conn        live source connection (already opened by the caller)
     * @param ownerTables each element is {@code [owner, table]} UPPERCASE — the tables the
     *                    project intends to capture; may be empty (e.g. connection test
     *                    before any project is configured) in which case table-level checks
     *                    are skipped.
     */
    public CdcProbeResult probe(Connection conn, List<String[]> ownerTables) {
        return probe(conn, ownerTables, null);
    }

    /**
     * P0-2 variant carrying the saved config: on a CDB the probe also reports how mining
     * will reach CDB$ROOT (or why it can't). A null config downgrades that check to
     * "no mining account configured", matching what the consumer would conclude.
     */
    public CdcProbeResult probe(Connection conn, List<String[]> ownerTables,
                                com.qqmu.jync.model.DatabaseConfig config) {
        MiningDialect dialect;
        try {
            dialect = MiningDialects.detect(conn);
        } catch (SQLException e) {
            // Neither redo catalogue answered: not a LogMiner source at all. One failing
            // check says that; the remaining checks would only repeat the same error.
            return CdcProbeResult.of(List.of(CdcProbeResult.Check.of(
                    CdcProbeResult.ORACLE_SOURCE, false, msg(e))));
        }
        if (dialect instanceof DmLogMinerDialect) {
            return probeDm(conn);
        }
        return probeOracle(conn, ownerTables, config);
    }

    private CdcProbeResult probeOracle(Connection conn, List<String[]> ownerTables,
                                       com.qqmu.jync.model.DatabaseConfig config) {
        CdcProbeResult.Check source = checkOracleSource(conn);
        if (!source.isPassed()) {
            // A product that only ANSWERS like Oracle has no measured mining path, and the
            // rest of the list would ask it Oracle questions anyway: YashanDB 23.4.7.100 has
            // no DBMS_LOGMNR at all, yet it happily reports LOG_MODE=ARCHIVELOG and
            // SUPPLEMENTAL_LOG_DATA_MIN=NO — which would earn a DBA the advice to enable
            // supplemental logging for a miner that does not exist. One verdict, no noise.
            return CdcProbeResult.of(List.of(source));
        }
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(source);
        int major = majorVersion(conn);
        checks.add(checkVersion(major));
        checks.add(checkArchivelog(conn));
        checks.add(checkSupplementalDb(conn));
        if (ownerTables != null && !ownerTables.isEmpty()) {
            checks.add(checkSupplementalTables(conn, ownerTables));
            checks.add(checkLongColumns(conn, ownerTables));
        }
        checks.add(checkMiningPrivileges(conn, major));
        CdcProbeResult.Check cdb = checkCdbMiningMode(conn, config);
        if (cdb != null) {
            checks.add(cdb);
        }
        return CdcProbeResult.of(checks);
    }

    /**
     * DM8 (docs/dm-logminer-spike.md). Same itemized shape, different questions: DM has no
     * per-table supplemental group (the system parameter does that job), only archived files
     * can be mined, and there is neither LONG nor a CDB — so the Oracle-only checks are not
     * reported at all rather than silently passing.
     */
    private CdcProbeResult probeDm(Connection conn) {
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(checkDmSource(conn));
        checks.add(checkDmVersion(conn));
        checks.add(checkDmArchMode(conn));
        checks.add(checkDmLogicRedo(conn));
        checks.add(checkDmArchivePeriod(conn));
        checks.add(checkDmMiningPrivileges(conn));
        return CdcProbeResult.of(checks);
    }

    // —— v1.9.0 DM ① source ——

    private CdcProbeResult.Check checkDmSource(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toUpperCase(Locale.ROOT).contains("DM")) {
                return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, false,
                        "product=" + product + " — answered the DM redo probe (V$RLOGFILE) but"
                                + " is not a product whose redo has been measured for mining;"
                                + " verified sources: Oracle and DM8");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, true,
                    product + " (DM LogMiner dialect)");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, false, msg(e));
        }
    }

    // —— v1.9.0 DM ② version (reported, not gated: the measured instance is 8.1.2) ——

    private CdcProbeResult.Check checkDmVersion(Connection conn) {
        String version;
        try {
            version = conn.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            version = null;
        }
        return CdcProbeResult.Check.of(CdcProbeResult.VERSION, true,
                version == null ? "version unreadable" : "DM " + version);
    }

    // —— v1.9.0 DM ③ archiving: V$DATABASE.ARCH_MODE (DM has no LOG_MODE) ——

    private CdcProbeResult.Check checkDmArchMode(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ARCH_MODE FROM V$DATABASE")) {
            String mode = rs.next() ? rs.getString(1) : null;
            boolean ok = "Y".equalsIgnoreCase(mode);
            return CdcProbeResult.Check.of(CdcProbeResult.ARCHIVELOG, ok,
                    ok ? "ARCH_MODE=" + mode
                            : "ARCH_MODE=" + mode + " — enable archiving: add an ARCH_TYPE=LOCAL,"
                            + " ARCH_DEST=<path> stanza to dmarch.ini, set ARCH_INI=1 in dm.ini,"
                            + " restart the instance, then run ALTER SYSTEM SWITCH LOGFILE;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.ARCHIVELOG, false, msg(e));
        }
    }

    // —— v1.9.0 DM ④ full old image in redo: RLOG_APPEND_LOGIC = 2 ——

    private CdcProbeResult.Check checkDmLogicRedo(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT PARA_VALUE FROM V$DM_INI"
                     + " WHERE PARA_NAME='RLOG_APPEND_LOGIC'")) {
            String raw = rs.next() ? rs.getString(1) : null;
            String value = raw == null ? null : raw.trim();
            boolean ok = "2".equals(value);
            return CdcProbeResult.Check.of(CdcProbeResult.DM_LOGIC_REDO, ok,
                    ok ? "RLOG_APPEND_LOGIC=2 (full old image in redo)"
                            : "RLOG_APPEND_LOGIC=" + raw + " — only 2 writes the full old image;"
                            + " without it an UPDATE's redo carries just the changed columns and"
                            + " the upsert cannot apply the row; set it with CALL"
                            + " SP_SET_PARA_VALUE(1,'RLOG_APPEND_LOGIC',2); then restart the"
                            + " instance");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.DM_LOGIC_REDO, false, msg(e));
        }
    }

    // —— v1.9.0 DM ⑤ archive span and freshness (the latency floor) ——

    /** Above this age the newest archive is reported as a latency risk, not a failure. */
    private static final long DM_STALE_ARCHIVE_MS = 10 * 60 * 1000L;

    private CdcProbeResult.Check checkDmArchivePeriod(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), MIN(FIRST_CHANGE#),"
                     + " MAX(NEXT_CHANGE#), MAX(NEXT_TIME) FROM V$ARCHIVED_LOG")) {
            if (!rs.next() || rs.getLong(1) == 0L) {
                return CdcProbeResult.Check.of(CdcProbeResult.DM_ARCHIVE_PERIOD, false,
                        "no archived redo yet — DM can only mine archived files, so nothing is"
                                + " mineable until a redo file is switched and archived; run"
                                + " ALTER SYSTEM SWITCH LOGFILE (or schedule a job that does)");
            }
            long files = rs.getLong(1);
            long minScn = rs.getLong(2);
            long maxScn = rs.getLong(3);
            java.sql.Timestamp newest = rs.getTimestamp(4);
            String span = files + " file(s), SCN " + minScn + " .. " + maxScn;
            if (newest == null) {
                return CdcProbeResult.Check.of(CdcProbeResult.DM_ARCHIVE_PERIOD, true, span);
            }
            long ageMs = Math.max(0L, System.currentTimeMillis() - newest.getTime());
            String age = ageMs / 60_000L + " min";
            if (ageMs > DM_STALE_ARCHIVE_MS) {
                return CdcProbeResult.Check.of(CdcProbeResult.DM_ARCHIVE_PERIOD, true,
                        span + "; newest archived " + age + " ago — CDC lags behind by up to"
                                + " this interval, so schedule ALTER SYSTEM SWITCH LOGFILE"
                                + " (e.g. every minute) to bound it");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.DM_ARCHIVE_PERIOD, true,
                    span + "; newest archived " + age + " ago");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.DM_ARCHIVE_PERIOD, false, msg(e));
        }
    }

    // —— v1.9.0 DM ⑥ mining privileges ——

    private CdcProbeResult.Check checkDmMiningPrivileges(Connection conn) {
        try {
            // Reading the archived-file catalogue is the permission every mining cycle
            // leans on; on the measured instance it is granted to PUBLIC by default.
            count(conn, "SELECT COUNT(*) FROM V$ARCHIVED_LOG");
            long grants = count(conn, "SELECT COUNT(*) FROM DBA_TAB_PRIVS"
                    + " WHERE TABLE_NAME='DBMS_LOGMNR' AND PRIVILEGE='EXECUTE'"
                    + " AND (GRANTEE = USER OR GRANTEE='PUBLIC'"
                    + " OR GRANTEE IN (SELECT ROLE FROM SESSION_ROLES))");
            if (grants > 0) {
                return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, true,
                        "V$ARCHIVED_LOG readable and EXECUTE ON SYS.DBMS_LOGMNR granted");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, false,
                    "missing EXECUTE ON SYS.DBMS_LOGMNR — GRANT EXECUTE ON"
                            + " SYS.DBMS_LOGMNR TO <user>;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, false,
                    "cannot read the redo catalogue (" + msg(e) + ") — the mining account needs"
                            + " SELECT on V$ARCHIVED_LOG / V$LOGMNR_CONTENTS and EXECUTE ON"
                            + " SYS.DBMS_LOGMNR");
        }
    }

    /**
     * P0-2: CDB mining mode — null on a non-CDB (or when the CDB flag is unreadable:
     * 11g has no such column, and guessing would misreport). On a CDB: root connection
     * passes with a scope note (only root-container objects are captured); a PDB
     * connection passes only when a mining account is configured, otherwise it fails
     * with the ORA-65040 the first mine would otherwise die with.
     */
    private CdcProbeResult.Check checkCdbMiningMode(
            Connection conn, com.qqmu.jync.model.DatabaseConfig config) {
        if (!Boolean.TRUE.equals(OracleCdbSupport.isCdb(conn))) {
            return null;
        }
        int conId = OracleCdbSupport.currentConId(conn);
        if (conId == 1) {
            return CdcProbeResult.Check.of(CdcProbeResult.CDB_MINING_MODE, true,
                    "CDB — connected to CDB$ROOT directly: only root-container objects"
                            + " are captured; to mine a PDB, configure its service plus"
                            + " the mining account");
        }
        boolean minerConfigured = config != null
                && config.getMinerUsername() != null && !config.getMinerUsername().isBlank()
                && config.getMinerPassword() != null && !config.getMinerPassword().isBlank();
        if (minerConfigured) {
            return CdcProbeResult.Check.of(CdcProbeResult.CDB_MINING_MODE, true,
                    "CDB (CON_ID " + conId + ") — mining via CDB$ROOT as '"
                            + config.getMinerUsername() + "'");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.CDB_MINING_MODE, false,
                "CDB (CON_ID " + conId + ") — DBMS_LOGMNR may only run in CDB$ROOT, so the"
                        + " first mine raises ORA-65040; configure the miner"
                        + " username/password (a C## common user) on this connection");
    }

    // —— ① verified product (v1.9.0: Oracle, or DM through its own probe) ——

    private CdcProbeResult.Check checkOracleSource(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toUpperCase(Locale.ROOT).contains("ORACLE")) {
                return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, false,
                        "product=" + product + " — answered the Oracle redo probe, but only"
                                + " Oracle and DM8 (which has its own dialect) have a measured"
                                + " mining path. YashanDB 23.4.7.100 was measured on"
                                + " 2026-10-29 and ships no SYS.DBMS_LOGMNR and no"
                                + " V$LOGMNR_CONTENTS, so redo mining there cannot be enabled by"
                                + " configuration; other Oracle-mode look-alikes (e.g."
                                + " OceanBase) are refused until measured. Capture this source"
                                + " with polling (timestamp column or numeric key) or full"
                                + " comparison instead");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, true, product);
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, false, msg(e));
        }
    }

    // —— ② version ≥ 11.2 ——

    private CdcProbeResult.Check checkVersion(int major) {
        // 0 = unknown (metadata error in ① already flagged it); treat as unreadably old.
        boolean ok = major >= 11;
        String detail = major == 0
                ? "could not read product major version"
                : "major=" + major;
        return CdcProbeResult.Check.of(CdcProbeResult.VERSION, ok,
                ok ? detail : detail + " — Oracle 11.2 or newer required");
    }

    // —— ③ ARCHIVELOG ——

    private CdcProbeResult.Check checkArchivelog(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT LOG_MODE FROM V$DATABASE")) {
            String mode = rs.next() ? rs.getString(1) : null;
            boolean ok = "ARCHIVELOG".equalsIgnoreCase(mode);
            return CdcProbeResult.Check.of(CdcProbeResult.ARCHIVELOG, ok,
                    ok ? "LOG_MODE=" + mode
                            : "LOG_MODE=" + mode + " — enable with: SHUTDOWN IMMEDIATE; STARTUP MOUNT;"
                            + " ALTER DATABASE ARCHIVELOG; ALTER DATABASE OPEN;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.ARCHIVELOG, false, msg(e));
        }
    }

    // —— ④ database-level supplemental logging ——

    private CdcProbeResult.Check checkSupplementalDb(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SUPPLEMENTAL_LOG_DATA_MIN FROM V$DATABASE")) {
            String value = rs.next() ? rs.getString(1) : null;
            boolean ok = value != null && !"NO".equalsIgnoreCase(value);
            return CdcProbeResult.Check.of(CdcProbeResult.SUPPLEMENTAL_DB, ok,
                    ok ? "SUPPLEMENTAL_LOG_DATA_MIN=" + value
                            : "SUPPLEMENTAL_LOG_DATA_MIN=" + value
                                    + " — enable with: ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.SUPPLEMENTAL_DB, false, msg(e));
        }
    }

    // —— ⑤ per-table ALL COLUMN logging group ——

    private CdcProbeResult.Check checkSupplementalTables(Connection conn, List<String[]> ownerTables) {
        List<String> missing = new ArrayList<>();
        for (String[] ot : ownerTables) {
            String owner = ot[0];
            String table = ot[1];
            try (PreparedStatementLike ps = prepared(conn,
                    "SELECT COUNT(*) FROM ALL_LOG_GROUPS WHERE OWNER=? AND TABLE_NAME=?"
                            + " AND LOG_GROUP_TYPE='ALL COLUMN LOGGING'")) {
                ps.setString(1, owner);
                ps.setString(2, table);
                ResultSet rs = ps.executeQuery();
                long count = rs.next() ? rs.getLong(1) : 0L;
                if (count == 0L) {
                    missing.add(owner + "." + table);
                }
            } catch (SQLException e) {
                return CdcProbeResult.Check.of(CdcProbeResult.SUPPLEMENTAL_TABLES, false, msg(e));
            }
        }
        if (missing.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.SUPPLEMENTAL_TABLES, true,
                    ownerTables.size() + " table(s) with ALL COLUMN LOGGING");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.SUPPLEMENTAL_TABLES, false,
                "missing ALL COLUMN LOGGING on: " + String.join(", ", missing)
                        + " — add with: ALTER TABLE \"<OWNER>\".\"<TABLE>\""
                        + " ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;");
    }

    // —— ⑦ LONG / LONG RAW columns (warning, not a blocker) ——

    private CdcProbeResult.Check checkLongColumns(Connection conn, List<String[]> ownerTables) {
        List<String> longCols = new ArrayList<>();
        for (String[] ot : ownerTables) {
            try (PreparedStatementLike ps = prepared(conn,
                    "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=?"
                            + " AND DATA_TYPE IN ('LONG','LONG RAW')")) {
                ps.setString(1, ot[0]);
                ps.setString(2, ot[1]);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    longCols.add(ot[0] + "." + ot[1] + "." + rs.getString(1));
                }
            } catch (SQLException e) {
                return CdcProbeResult.Check.of(CdcProbeResult.LONG_COLUMNS, false, msg(e));
            }
        }
        if (longCols.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.LONG_COLUMNS, true, "no LONG columns");
        }
        // PASS with warning: those columns' DML is skipped and counted, not fatal.
        return CdcProbeResult.Check.of(CdcProbeResult.LONG_COLUMNS, true,
                "LONG columns (their DML will be skipped and counted): "
                        + String.join(", ", longCols));
    }

    // —— ⑥ mining privileges ——

    private CdcProbeResult.Check checkMiningPrivileges(Connection conn, int major) {
        boolean is11g = major > 0 && major < 12;
        String roleSql;
        String expected;
        String roleGrant;
        if (is11g) {
            roleSql = "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN"
                    + " ('EXECUTE_CATALOG_ROLE','SELECT_CATALOG_ROLE','DBA')";
            expected = "EXECUTE_CATALOG_ROLE / SELECT_CATALOG_ROLE (or DBA)";
            roleGrant = "EXECUTE_CATALOG_ROLE";
        } else {
            roleSql = "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN ('LOGMINING','DBA')";
            expected = "LOGMINING role (or DBA)";
            roleGrant = "LOGMINING";
        }
        try {
            boolean hasRole = count(conn, roleSql) > 0;
            // Oracle 11.2.0.2 实测:持有目录角色 + 直接 SELECT ON V_$LOGMNR_CONTENTS,
            // START_LOGMNR 之后读该视图仍报 ORA-01031,授予 SELECT ANY TRANSACTION 才通。
            // 12c+ 的 LOGMINING 角色同样不隐含它(IT 里也是单独授予),所以各版本都查。
            // DBA 角色自带该权限,按角色连库的 DBA 用户不受影响。
            boolean hasSelectAnyTxn = count(conn,
                    "SELECT COUNT(*) FROM SESSION_PRIVS"
                            + " WHERE PRIVILEGE = 'SELECT ANY TRANSACTION'") > 0;
            if (hasRole && hasSelectAnyTxn) {
                return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, true,
                        "has " + expected + " and SELECT ANY TRANSACTION");
            }
            List<String> missing = new ArrayList<>();
            if (!hasRole) {
                missing.add("missing " + expected + " — GRANT " + roleGrant + " TO <user>");
            }
            if (!hasSelectAnyTxn) {
                missing.add("missing SELECT ANY TRANSACTION — without it every read of"
                        + " V_$LOGMNR_CONTENTS after START_LOGMNR fails with ORA-01031;"
                        + " GRANT SELECT ANY TRANSACTION TO <user>");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, false,
                    String.join("; ", missing)
                            + "; plus EXECUTE ON SYS.DBMS_LOGMNR and SELECT on"
                            + " V_\\$DATABASE / V_\\$LOG / V_\\$LOGFILE / V_\\$ARCHIVED_LOG / V_\\$LOGMNR_CONTENTS");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, false, msg(e));
        }
    }

    /** First-column COUNT(*) of a scalar query; 0 when the query returns no row. */
    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // —— helpers ——

    private static int majorVersion(Connection conn) {
        try {
            String version = conn.getMetaData().getDatabaseProductVersion();
            if (version == null) {
                return 0;
            }
            StringBuilder digits = new StringBuilder();
            for (char c : version.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * Tiny wrapper so the check method can use try-with-resources without pulling a real
     * PreparedStatement into every catch. The wrapper closes the underlying statement
     * (and any open ResultSet produced from it) on close.
     */
    private static PreparedStatementLike prepared(Connection conn, String sql) throws SQLException {
        return new PreparedStatementLike(conn.prepareStatement(sql));
    }

    private static String msg(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static final class PreparedStatementLike implements AutoCloseable {
        private final java.sql.PreparedStatement delegate;

        private PreparedStatementLike(java.sql.PreparedStatement delegate) {
            this.delegate = delegate;
        }

        void setString(int i, String v) throws SQLException {
            delegate.setString(i, v);
        }

        ResultSet executeQuery() throws SQLException {
            return delegate.executeQuery();
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }
    }
}
