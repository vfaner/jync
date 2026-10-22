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
 * v1.7.0 SQL Server CDC readiness probe — the MSSQL twin of {@link LogMinerCdcProbe}
 * and {@link PgLogicalReplicationCdcProbe}.
 *
 * <p>Itemized, never throws. Checks:
 * <ol>
 *   <li>literal Microsoft SQL Server;</li>
 *   <li>database-level CDC enabled ({@code sys.databases.is_cdc_enabled});</li>
 *   <li>SQL Server Agent running (required for capture / cleanup jobs) — on Azure SQL
 *       Database (EngineEdition=5) this fails outright: CDC is unsupported there (P1-9);</li>
 *   <li>current user is in {@code cdc_admin} / {@code cdc_reader} (or {@code sysadmin});</li>
 *   <li>per-table capture instance exists in {@code cdc.change_tables}.</li>
 * </ol>
 */
@Component
@Slf4j
public class SqlServerCdcProbe {

    /**
     * @param conn        live source connection (already opened by the caller)
     * @param schemaTables each element is {@code [schema, table]} — the tables the project
     *                    intends to capture; may be empty (connection test before any
     *                    project is configured) in which case the table-level check is skipped.
     */
    public CdcProbeResult probe(Connection conn, List<String[]> schemaTables) {
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(checkMssqlSource(conn));
        checks.add(checkCdcDbEnabled(conn));
        checks.add(checkAgentOrAzure(conn));
        checks.add(checkCdcRole(conn));
        if (schemaTables != null && !schemaTables.isEmpty()) {
            checks.add(checkCaptureInstances(conn, schemaTables));
        }
        return CdcProbeResult.of(checks);
    }

    // —— ① literal SQL Server ——

    private CdcProbeResult.Check checkMssqlSource(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null
                    || !product.toUpperCase(Locale.ROOT).contains("SQL SERVER")) {
                return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_SOURCE, false,
                        "product=" + product + " — SQL Server CDC requires literal"
                                + " Microsoft SQL Server");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_SOURCE, true, product);
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_SOURCE, false, msg(e));
        }
    }

    // —— ② database CDC enabled ——

    private CdcProbeResult.Check checkCdcDbEnabled(Connection conn) {
        String sql = "SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            boolean enabled = rs.next() && rs.getBoolean(1);
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CDC_DB_ENABLED, enabled,
                    enabled ? "is_cdc_enabled=1"
                            : "is_cdc_enabled=0 — enable with: EXEC sys.sp_cdc_enable_db;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CDC_DB_ENABLED, false, msg(e));
        }
    }

    // —— ③ SQL Server Agent running (Azure SQL DB: hard fail instead) ——

    /**
     * P1-9: Azure SQL Database (EngineEdition=5) does not support SQL Server CDC at all —
     * there is no SQL Server Agent to install or start, so the dm_server_services
     * remediation ("install/start it") is a dead end there. Detect the edition first and
     * fail with an honest message; otherwise run the normal Agent check.
     */
    private CdcProbeResult.Check checkAgentOrAzure(Connection conn) {
        Integer edition = readEngineEdition(conn);
        if (edition != null && edition == 5) {
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_AGENT_RUNNING, false,
                    "EngineEdition=5 — Azure SQL Database does not support SQL Server CDC"
                            + " (no SQL Server Agent to run the capture jobs); use Azure"
                            + " SQL Managed Instance, or switch this project to the"
                            + " polling strategy");
        }
        return checkAgentRunning(conn);
    }

    /**
     * SERVERPROPERTY('EngineEdition') — null when undeterminable (old driver, missing
     * permission, misbehaving mock): the caller then conservatively runs the normal
     * Agent check instead of guessing.
     */
    private static Integer readEngineEdition(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SERVERPROPERTY('EngineEdition')")) {
            return rs != null && rs.next() ? (Integer) rs.getInt(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private CdcProbeResult.Check checkAgentRunning(Connection conn) {
        // On Linux SQL Server (mssql-server >= 2019) the Agent is optional; on Windows it
        // is a service. dm_server_services lists it when present; absent means Agent is
        // not installed (required for CDC capture/cleanup jobs).
        String sql = "SELECT status_desc FROM sys.dm_server_services"
                + " WHERE servicename LIKE N'SQL Server Agent%'";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            boolean running = false;
            String status = null;
            while (rs.next()) {
                status = rs.getString(1);
                if ("RUNNING".equalsIgnoreCase(status)) running = true;
            }
            if (status == null) {
                return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_AGENT_RUNNING, false,
                        "SQL Server Agent service not found — install/start it"
                                + " (capture and cleanup jobs require the Agent)");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_AGENT_RUNNING, running,
                    "Agent status=" + status
                            + (running ? "" : " — start the SQL Server Agent service"));
        } catch (SQLException e) {
            // dm_server_services requires VIEW SERVER STATE; degrade to pass-with-warning.
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_AGENT_RUNNING, true,
                    "Agent status unknown (VIEW SERVER STATE missing?): " + msg(e));
        }
    }

    // —— ④ CDC role ——

    private CdcProbeResult.Check checkCdcRole(Connection conn) {
        String sql = "SELECT IS_MEMBER('cdc_Admin') AS is_admin, IS_MEMBER('cdc_reader') AS is_reader,"
                + " IS_SRVROLEMEMBER('sysadmin') AS is_sa";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            boolean admin = false, reader = false, sa = false;
            if (rs.next()) {
                admin = rs.getInt(1) == 1;
                reader = rs.getInt(2) == 1;
                sa = rs.getInt(3) == 1;
            }
            boolean ok = admin || reader || sa;
            String detail = "cdc_Admin=" + admin + ", cdc_reader=" + reader + ", sysadmin=" + sa;
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CDC_ROLE, ok,
                    ok ? detail
                            : detail + " — grant with: CREATE ROLE cdc_reader;"
                                    + " ALTER ROLE cdc_reader ADD MEMBER [<user>];");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CDC_ROLE, false, msg(e));
        }
    }

    // —— ⑤ per-table capture instance + actual read permission ——

    private CdcProbeResult.Check checkCaptureInstances(Connection conn, List<String[]> schemaTables) {
        List<String> missing = new ArrayList<>();
        List<String> ciNames = new ArrayList<>();
        for (String[] st : schemaTables) {
            String schema = st[0];
            String table = st[1];
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT capture_instance FROM cdc.change_tables"
                            + " WHERE source_schema_name = ? AND source_table_name = ?")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        missing.add(schema + "." + table);
                    } else {
                        ciNames.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CAPTURE_INSTANCE, false,
                        msg(e));
            }
        }
        if (!missing.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CAPTURE_INSTANCE, false,
                    "missing CDC capture instance on: " + String.join(", ", missing)
                            + " — enable with: EXEC sys.sp_cdc_enable_table"
                            + " @source_schema=N'<schema>', @source_name=N'<table>',"
                            + " @role_name=N'cdc_Admin', @capture_instance=N'<schema>_<table>';");
        }
        // Role membership (check ④) only tells us the user is in cdc_Admin/cdc_reader.
        // It does NOT prove the user can actually EXECUTE fn_cdc_get_all_changes_<ci> —
        // that depends on @role_name chosen at enable_table time. Probe with a real
        // top-1 call; if even this 0-row call throws, runtime will fail on the first
        // drain, so fail early.
        List<String> unprobed = new ArrayList<>();
        for (String ci : ciNames) {
            // P1-7: same whitelist the consumer enforces (isSafeCaptureInstanceName) —
            // the name is inlined into the fn call below, and a CI outside [A-Za-z0-9_]
            // can never be synced at runtime anyway. Failing here keeps the probe honest:
            // a green probe must mean the consumer will actually stream this table.
            if (!SqlServerCdcConsumer.isSafeCaptureInstanceName(ci)) {
                return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CAPTURE_INSTANCE, false,
                        "capture instance " + ci + " has a name outside [A-Za-z0-9_]"
                                + " — Jync refuses to inline it into SQL, so its changes"
                                + " can never be synced; recreate it with"
                                + " sys.sp_cdc_enable_table @capture_instance=N'<schema>_<table>'");
            }
            try {
                // A capture instance that has captured NOTHING yet cannot be probed:
                // fn_cdc_get_max_lsn() is NULL, and EVERY fn_cdc_get_all_changes call
                // then raises Msg 313 (verified on SQL Server 2022 — even a zero-width
                // range). Reporting that as "not readable, check @role_name" sends the
                // DBA after a permission problem that does not exist on a perfectly
                // fresh setup. Skip the probe; check ③ tells them if the Agent is down.
                if (!hasCapturedChanges(conn, ci)) {
                    unprobed.add(ci);
                    continue;
                }
                String sql = "SELECT TOP(1) * FROM cdc.fn_cdc_get_all_changes_" + ci
                        + "(sys.fn_cdc_get_min_lsn(?), sys.fn_cdc_get_max_lsn(), 'all')";
                try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, ci);
                    try (ResultSet rs = ps.executeQuery()) {
                        // 0 rows is fine — we only care that the function is callable.
                    }
                }
            } catch (SQLException e) {
                return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CAPTURE_INSTANCE, false,
                        "capture instance " + ci + " exists but is not readable: " + msg(e)
                                + " — check @role_name on sp_cdc_enable_table");
            }
        }
        if (unprobed.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CAPTURE_INSTANCE, true,
                    schemaTables.size() + " table(s) with CDC capture instance (readable)");
        }
        int readable = ciNames.size() - unprobed.size();
        return CdcProbeResult.Check.of(CdcProbeResult.MSSQL_CAPTURE_INSTANCE, true,
                schemaTables.size() + " table(s) with CDC capture instance ("
                        + readable + " readable, " + unprobed.size()
                        + " not probed: no change captured yet — start the SQL Server"
                        + " Agent so the capture job runs)");
    }

    /** min_lsn and max_lsn both non-NULL ⇔ the capture table holds at least one change. */
    private static boolean hasCapturedChanges(Connection conn, String captureInstance)
            throws SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT sys.fn_cdc_get_min_lsn(?) AS min_lsn,"
                        + " sys.fn_cdc_get_max_lsn() AS max_lsn")) {
            ps.setString(1, captureInstance);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBytes(1) != null && rs.getBytes(2) != null;
            }
        }
    }

    private static String msg(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
