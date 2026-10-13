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
 * <p>Scope: literal Oracle only (DM / OceanBase Oracle-compatible dialects are rejected
 * by {@link CdcProbeResult#ORACLE_SOURCE}); 11g and 12c+ are version-adaptive on the
 * mining-role check (⑥).
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
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(checkOracleSource(conn));
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

    // —— ① literal Oracle ——

    private CdcProbeResult.Check checkOracleSource(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toUpperCase(Locale.ROOT).contains("ORACLE")) {
                return CdcProbeResult.Check.of(CdcProbeResult.ORACLE_SOURCE, false,
                        "product=" + product + " — LogMiner CDC requires literal Oracle; "
                                + "dialect-compatible databases (DM / OceanBase Oracle mode) are out of scope");
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
        if (is11g) {
            roleSql = "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN"
                    + " ('EXECUTE_CATALOG_ROLE','SELECT_CATALOG_ROLE','DBA')";
            expected = "EXECUTE_CATALOG_ROLE / SELECT_CATALOG_ROLE (or DBA)";
        } else {
            roleSql = "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN ('LOGMINING','DBA')";
            expected = "LOGMINING role (or DBA)";
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(roleSql)) {
            long count = rs.next() ? rs.getLong(1) : 0L;
            boolean ok = count > 0;
            return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, ok,
                    ok ? "has " + expected
                            : "missing " + expected
                                    + " — grant with: GRANT " + (is11g ? "EXECUTE_CATALOG_ROLE" : "LOGMINING")
                                    + " TO <user>; plus EXECUTE ON SYS.DBMS_LOGMNR and SELECT on"
                                    + " V_\\$DATABASE / V_\\$LOG / V_\\$LOGFILE / V_\\$ARCHIVED_LOG / V_\\$LOGMNR_CONTENTS");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.MINING_PRIVILEGES, false, msg(e));
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
