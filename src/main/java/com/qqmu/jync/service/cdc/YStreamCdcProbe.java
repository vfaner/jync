package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * v1.9.0 YashanDB YStream readiness probe.
 *
 * <p>Itemized and never throwing, like every other CDC probe: an unready source degrades to
 * failed checks carrying the remediation a DBA can act on. Check order is report order.
 *
 * <p>Read-only by construction — it reads the catalogue and the admin views, and never
 * creates a service, adds a table or runs a DDL statement. The one remediation that must be
 * run by a human (per-table supplemental logging) is only <em>reported</em>, as a copyable
 * statement.
 *
 * <p>Measured facts behind the checks (YashanDB 23.4.7.100, docs/dm-logminer-spike.md):
 * {@code DBMS_YSTREAM_ADM} is a built-in package invisible in {@code DBA_OBJECTS}, so the
 * catalog cannot tell whether YStream exists — {@code V$YSTREAM_SERVER} is the observable
 * proxy, and it needs {@code SELECT ANY DICTIONARY} to be readable at all. Supplemental
 * logging is per table and a missing group makes DML vanish silently. {@code V$DATABASE}
 * carries both {@code LOG_MODE} and {@code DATABASE_ROLE}. There is no {@code SESSION_PRIVS}
 * / {@code SESSION_ROLES} view, so effective privileges are assembled from three views.
 */
@Component
@Slf4j
public class YStreamCdcProbe {

    /** Both are hard requirements: one to capture, one to see the admin views. */
    static final List<String> REQUIRED_PRIVILEGES =
            List.of("YSTREAM_CAPTURE", "SELECT ANY DICTIONARY");

    /** Above this many names the report stops listing and says how many remain. */
    private static final int MAX_LISTED_TABLES = 5;

    /**
     * @param conn        live source connection (the caller owns it)
     * @param ownerTables {@code [schema, table]} pairs in dictionary spelling; empty means
     *                    "no selection known yet" (the connection-test page), in which case
     *                    the two table-level checks are skipped rather than failed
     */
    public CdcProbeResult probe(Connection conn, List<String[]> ownerTables) {
        CdcProbeResult.Check source = checkSource(conn);
        if (!source.isPassed()) {
            // Either it is not Yashan at all, or it is one whose admin views this account
            // cannot read. Asking the remaining questions would only dress up the same
            // answer.
            return CdcProbeResult.of(List.of(source));
        }
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(source);
        checks.addAll(checkDatabase(conn));
        checks.add(checkPrivileges(conn));
        if (ownerTables != null && !ownerTables.isEmpty()) {
            checks.add(checkTableLogging(conn, ownerTables));
            checks.add(checkPrimaryKeys(conn, ownerTables));
        }
        checks.add(checkCapacity(conn));
        return CdcProbeResult.of(checks);
    }

    // —— ① product + admin view ——

    private CdcProbeResult.Check checkSource(Connection conn) {
        String product;
        try {
            product = conn.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_SOURCE, false, msg(e));
        }
        if (product == null || !product.toUpperCase(Locale.ROOT).contains("YASHAN")) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_SOURCE, false,
                    "product=" + product + " — YStream exists only on YashanDB; use polling"
                            + " or full comparison for this source");
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM V$YSTREAM_SERVER")) {
            long services = rs.next() ? rs.getLong(1) : 0L;
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_SOURCE, true,
                    product + " — V$YSTREAM_SERVER readable, " + services + " service(s)"
                            + " defined");
        } catch (SQLException e) {
            // The client would still connect without this grant; only the admin views go
            // dark, which is why it is a required privilege rather than a detail.
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_SOURCE, false,
                    "cannot read V$YSTREAM_SERVER (" + msg(e) + ") — the capture account"
                            + " needs SELECT ANY DICTIONARY, or YStream's admin views look"
                            + " like they do not exist even though the client still connects");
        }
    }

    // —— ② archiving and ③ primary (one V$DATABASE read) ——

    private List<CdcProbeResult.Check> checkDatabase(Connection conn) {
        String logMode;
        String role;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT LOG_MODE, DATABASE_ROLE FROM V$DATABASE")) {
            if (!rs.next()) {
                String detail = "V$DATABASE returned no row";
                return List.of(
                        CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_ARCHIVELOG, false, detail),
                        CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIMARY, false, detail));
            }
            logMode = rs.getString(1);
            role = rs.getString(2);
        } catch (SQLException e) {
            String detail = msg(e);
            return List.of(
                    CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_ARCHIVELOG, false, detail),
                    CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIMARY, false, detail));
        }
        boolean archived = "ARCHIVELOG".equalsIgnoreCase(logMode);
        boolean primary = "PRIMARY".equalsIgnoreCase(role);
        return List.of(
                CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_ARCHIVELOG, archived,
                        archived ? "LOG_MODE=" + logMode
                                : "LOG_MODE=" + logMode + " — switch the database to"
                                + " ARCHIVELOG (needs a restart) before creating a YStream"
                                + " service"),
                CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIMARY, primary,
                        primary ? "DATABASE_ROLE=" + role
                                : "DATABASE_ROLE=" + role + " — a YStream service can only be"
                                + " created on the primary; capture this replica from its"
                                + " primary or use polling"));
    }

    // —— ④ effective privileges ——

    private CdcProbeResult.Check checkPrivileges(Connection conn) {
        Set<String> held;
        Set<String> heldRoles;
        try {
            held = readStrings(conn, "SELECT PRIVILEGE FROM USER_SYS_PRIVS", 1);
            heldRoles = readStrings(conn, "SELECT GRANTED_ROLE FROM USER_ROLE_PRIVS", 1);
            Set<String> viaRoles = new LinkedHashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT ROLE, PRIVILEGE FROM ROLE_SYS_PRIVS")) {
                while (rs.next()) {
                    String role = rs.getString(1);
                    String privilege = rs.getString(2);
                    // ROLE_SYS_PRIVS lists every grant on the instance; only roles this
                    // user actually holds confer anything.
                    if (role != null && heldRoles.contains(role.toUpperCase(Locale.ROOT))
                            && privilege != null) {
                        viaRoles.add(privilege.toUpperCase(Locale.ROOT));
                    }
                }
            }
            held.addAll(viaRoles);
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIVILEGES, false,
                    "cannot read the privilege catalogue (" + msg(e) + ") — YashanDB has no"
                            + " SESSION_PRIVS/SESSION_ROLES; the probe needs USER_SYS_PRIVS,"
                            + " ROLE_SYS_PRIVS and USER_ROLE_PRIVS");
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_PRIVILEGES) {
            if (!held.contains(required)) {
                missing.add(required);
            }
        }
        if (missing.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIVILEGES, true,
                    "YSTREAM_CAPTURE and SELECT ANY DICTIONARY held (directly or via a"
                            + " granted role); CREATE SESSION and SELECT ANY TABLE are also"
                            + " needed on the capture account");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIVILEGES, false,
                "missing " + String.join(" and ", missing) + " — GRANT " + String.join(", ",
                missing) + " TO <user>; CREATE SESSION and SELECT ANY TABLE are required too");
    }

    // —— ⑤ per-table supplemental logging ——

    private CdcProbeResult.Check checkTableLogging(Connection conn, List<String[]> ownerTables) {
        Set<String> logged;
        try {
            logged = readOwnerTables(conn,
                    "SELECT OWNER, TABLE_NAME FROM ALL_LOG_GROUPS WHERE LOG_GROUP_TYPE='ALL'");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_TABLE_LOGGING, false, msg(e));
        }
        List<String[]> missing = new ArrayList<>();
        for (String[] table : ownerTables) {
            if (!logged.contains(key(table))) {
                missing.add(table);
            }
        }
        if (missing.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_TABLE_LOGGING, true,
                    ownerTables.size() + " table(s) carry an (ALL) COLUMNS supplemental"
                            + " group");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_TABLE_LOGGING, false,
                summarize(missing) + " — without table-level supplemental logging their DML"
                        + " never enters the stream (the service still reports RUNNING). Have"
                        + " a DBA run:\n" + alterScript(missing));
    }

    // —— ⑥ primary keys ——

    private CdcProbeResult.Check checkPrimaryKeys(Connection conn, List<String[]> ownerTables) {
        Set<String> withPk;
        try {
            withPk = readOwnerTables(conn,
                    "SELECT OWNER, TABLE_NAME FROM ALL_CONSTRAINTS WHERE CONSTRAINT_TYPE='P'");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIMARY_KEY, false, msg(e));
        }
        List<String[]> missing = new ArrayList<>();
        for (String[] table : ownerTables) {
            if (!withPk.contains(key(table))) {
                missing.add(table);
            }
        }
        if (missing.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIMARY_KEY, true,
                    ownerTables.size() + " table(s) have a primary key");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_PRIMARY_KEY, false,
                summarize(missing) + " — a DELETE/UPDATE row carries no unique identity"
                        + " without a primary key, so it cannot be applied; add one or exclude"
                        + " the table from the selection");
    }

    // —— ⑦ service capacity ——

    private CdcProbeResult.Check checkCapacity(Connection conn) {
        int used;
        try {
            used = YStreamServiceAdmin.serviceCount(conn);
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_SERVICE_CAPACITY, false,
                    msg(e));
        }
        boolean ok = used < YStreamServiceAdmin.MAX_SERVICES;
        return CdcProbeResult.Check.of(CdcProbeResult.YSTREAM_SERVICE_CAPACITY, ok,
                used + "/" + YStreamServiceAdmin.MAX_SERVICES + " services used"
                        + (ok ? "" : " — no slot left for this project. Release one by"
                        + " deleting another YStream project, or by switching it to polling"));
    }

    // —— helpers ——

    private static Set<String> readStrings(Connection conn, String sql, int column)
            throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String value = rs.getString(column);
                if (value != null) {
                    values.add(value.toUpperCase(Locale.ROOT));
                }
            }
        }
        return values;
    }

    private static Set<String> readOwnerTables(Connection conn, String sql) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String owner = rs.getString(1);
                String table = rs.getString(2);
                if (owner != null && table != null) {
                    tables.add((owner + "." + table).toUpperCase(Locale.ROOT));
                }
            }
        }
        return tables;
    }

    private static String key(String[] table) {
        return (table[0] + "." + table[1]).toUpperCase(Locale.ROOT);
    }

    private static String summarize(List<String[]> tables) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tables.size() && i < MAX_LISTED_TABLES; i++) {
            names.add(tables.get(i)[0] + "." + tables.get(i)[1]);
        }
        String listed = String.join(", ", names);
        int remaining = tables.size() - names.size();
        return remaining > 0 ? listed + " and " + remaining + " more" : listed;
    }

    private static String alterScript(List<String[]> tables) {
        StringBuilder script = new StringBuilder();
        for (String[] table : tables) {
            if (script.length() > 0) {
                script.append('\n');
            }
            script.append("ALTER TABLE \"").append(table[0]).append("\".\"")
                    .append(table[1])
                    .append("\" ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;");
        }
        return script.toString();
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
