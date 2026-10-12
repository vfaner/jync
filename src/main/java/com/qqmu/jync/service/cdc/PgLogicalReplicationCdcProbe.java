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
 * v1.7.0 PostgreSQL logical-replication readiness probe — the PG twin of
 * {@link LogMinerCdcProbe}.
 *
 * <p>Itemized, never throws: a merely-unready source degrades to failed checks with
 * remediation text a DBA can act on. The consumer reads a {@code test_decoding} slot
 * via JDBC ({@code pg_logical_slot_get_changes}); the probe validates that the slot can
 * be created and that UPDATE/DELETE events will carry enough keys to drive upserts.
 *
 * <p>Scope: literal PostgreSQL only. openGauss / Kingbase / Highgo / Vastbase / DM
 * PG-compatible dialects are rejected by {@link CdcProbeResult#PG_SOURCE} unless the
 * caller has filtered them upstream already.
 */
@Component
@Slf4j
public class PgLogicalReplicationCdcProbe {

    /**
     * @param conn        live source connection (already opened by the caller)
     * @param ownerTables each element is {@code [schema, table]} lowercase — the tables the
     *                    project intends to capture; may be empty (connection test before
     *                    any project is configured) in which case table-level checks are
     *                    skipped.
     */
    public CdcProbeResult probe(Connection conn, List<String[]> ownerTables) {
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(checkPgSource(conn));
        checks.add(checkWalLevel(conn));
        checks.add(checkReplicationRole(conn));
        checks.add(checkPluginAvailable(conn));
        if (ownerTables != null && !ownerTables.isEmpty()) {
            checks.add(checkReplicaIdentity(conn, ownerTables));
        }
        return CdcProbeResult.of(checks);
    }

    // —— ① literal PostgreSQL ——

    private CdcProbeResult.Check checkPgSource(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null
                    || !product.toUpperCase(Locale.ROOT).contains("POSTGRESQL")) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, false,
                        "product=" + product + " — PG logical CDC requires literal"
                                + " PostgreSQL; PG-compatible dialects (openGauss / Kingbase"
                                + " / Highgo / Vastbase) are out of scope");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, true, product);
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, false, msg(e));
        }
    }

    // —— ② wal_level = logical ——

    private CdcProbeResult.Check checkWalLevel(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW wal_level")) {
            String value = rs.next() ? rs.getString(1) : null;
            boolean ok = "logical".equalsIgnoreCase(value);
            return CdcProbeResult.Check.of(CdcProbeResult.PG_WAL_LEVEL, ok,
                    ok ? "wal_level=" + value
                            : "wal_level=" + value
                                    + " — set wal_level='logical' in postgresql.conf and"
                                    + " restart the server");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_WAL_LEVEL, false, msg(e));
        }
    }

    // —— ③ REPLICATION role ——

    private CdcProbeResult.Check checkReplicationRole(Connection conn) {
        String sql = "SELECT rolreplication, rolsuper FROM pg_roles"
                + " WHERE rolname = CURRENT_USER";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            boolean repl = false;
            boolean superuser = false;
            if (rs.next()) {
                repl = rs.getBoolean(1);
                superuser = rs.getBoolean(2);
            }
            boolean ok = repl || superuser;
            String detail = "rolreplication=" + repl + ", rolsuper=" + superuser;
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICATION_ROLE, ok,
                    ok ? detail
                            : detail + " — grant with: ALTER ROLE <user> REPLICATION LOGIN;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICATION_ROLE, false, msg(e));
        }
    }

    // —— ④ test_decoding plugin available ——

    private CdcProbeResult.Check checkPluginAvailable(Connection conn) {
        // Probe by creating a throwaway slot with the test_decoding plugin and dropping it
        // immediately. A missing plugin surfaces as an SQL error; we convert it into a
        // failed check with remediation text instead of throwing.
        String slotName = "jync_probe_slot_" + System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT pg_create_logical_replication_slot('" + slotName
                    + "', 'test_decoding')");
            st.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
            return CdcProbeResult.Check.of(CdcProbeResult.PG_PLUGIN_AVAILABLE, true,
                    "test_decoding reachable (probe slot created + dropped)");
        } catch (SQLException e) {
            // Best-effort cleanup in case the slot was created but the drop failed.
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
            } catch (SQLException ignore) {
                // leave the slot for the DBA; the failed check already tells them
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_PLUGIN_AVAILABLE, false,
                    msg(e) + " — test_decoding is shipped with vanilla PG ≥ 9.4; if it"
                            + " was disabled, ensure shared_preload_libraries includes"
                            + " 'test_decoding' or use a custom output plugin");
        }
    }

    // —— ⑤ per-table REPLICA IDENTITY ——

    private CdcProbeResult.Check checkReplicaIdentity(Connection conn, List<String[]> ownerTables) {
        List<String> missing = new ArrayList<>();
        for (String[] ot : ownerTables) {
            String schema = ot[0];
            String table = ot[1];
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT c.relreplident FROM pg_catalog.pg_class c"
                            + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                            + " WHERE n.nspname = ? AND c.relname = ?")) {
                ps.setString(1, schema == null ? "public" : schema.toLowerCase(Locale.ROOT));
                ps.setString(2, table.toLowerCase(Locale.ROOT));
                ResultSet rs = ps.executeQuery();
                String ident = rs.next() ? rs.getString(1) : null;
                // 'd' = DEFAULT (primary key), 'f' = FULL, 'i' = NOTHING, 'n' = INDEX.
                // 'i' makes UPDATE/DELETE emit only the new row without enough keys to
                // locate the old row — consumer cannot upsert reliably.
                if (ident == null || "i".equals(ident)) {
                    missing.add((schema == null ? "public" : schema) + "." + table
                            + " (replica_identity=" + ident + ")");
                }
            } catch (SQLException e) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICA_IDENTITY, false,
                        msg(e));
            }
        }
        if (missing.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICA_IDENTITY, true,
                    ownerTables.size() + " table(s) with usable REPLICA IDENTITY");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICA_IDENTITY, false,
                "unsuitable REPLICA IDENTITY on: " + String.join(", ", missing)
                        + " — fix with: ALTER TABLE \"<schema>\".\"<table>\""
                        + " REPLICA IDENTITY DEFAULT;  -- or FULL if no PK");
    }

    private static String msg(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
