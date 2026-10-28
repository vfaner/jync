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
        checks.add(checkSlotHeadroom(conn));
        checks.add(checkPluginAvailable(conn));
        if (ownerTables != null && !ownerTables.isEmpty()) {
            checks.add(checkReplicaIdentity(conn, ownerTables));
        }
        return CdcProbeResult.of(checks);
    }

    // —— ① PG family (v1.9.0: name no longer decides, capability does) ——

    private CdcProbeResult.Check checkPgSource(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || product.isBlank()) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, false,
                        "the server did not report a product name — the PostgreSQL-family"
                                + " gate cannot be decided; check the JDBC URL and driver");
            }
            boolean openGauss = PgLogicalDialect.detect(product) == PgLogicalDialect.OPENGAUSS;
            return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, true,
                    product + (openGauss
                            ? " (openGauss-compatible; capabilities are verified by the checks"
                                    + " below, not by the product name)"
                            : ""));
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
            String detail = "rolreplication=" + repl + ", rolsuper=" + superuser;
            if (repl || superuser) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICATION_ROLE, true, detail);
            }
            // v1.9.0: openGauss 5.0.0 的初始超级用户两个属性都是 f，却照样能建逻辑槽
            // （docker 实测）。该内核不把 REPLICATION 属性当建槽门槛，硬性要求只会
            // 把所有 openGauss 源误判为不可用；真正的权威是 ⑤ 建槽+删槽探测。
            String product = conn.getMetaData() == null
                    ? null : conn.getMetaData().getDatabaseProductName();
            if (PgLogicalDialect.detect(product) == PgLogicalDialect.OPENGAUSS) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICATION_ROLE, true,
                        detail + " — openGauss does not require the REPLICATION attribute to"
                                + " create a logical slot; slot creation is verified below");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICATION_ROLE, false,
                    detail + " — grant with: ALTER ROLE <user> REPLICATION LOGIN;");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICATION_ROLE, false, msg(e));
        }
    }

    /**
     * openGauss-only advisory appended to a passing plugin check: with
     * {@code enable_slot_log=off} the slot still works (measured), but it does not
     * survive a server restart. Vanilla PostgreSQL has no such GUC — a failed query
     * simply yields no hint, and the check's verdict never depends on it.
     */
    private static String slotLogHint(Statement st) {
        try (ResultSet rs = st.executeQuery("SHOW enable_slot_log")) {
            String value = rs.next() ? rs.getString(1) : null;
            if (value != null && "off".equalsIgnoreCase(value)) {
                return "; enable_slot_log=off — enable it so the slot survives a server"
                        + " restart (after a restart the position is rebuilt from a baseline)";
            }
        } catch (SQLException | RuntimeException ignored) {
            // not openGauss — no hint to give
        }
        return "";
    }

    // —— ④ max_replication_slots headroom (P1-9/I6) ——

    private CdcProbeResult.Check checkSlotHeadroom(Connection conn) {
        // Every running PG CDC project holds one logical slot. Once the quota is
        // exhausted, pg_create_logical_replication_slot fails outright and the consumer
        // sits in a TRANSIENT retry loop — fail here instead, with the knob to turn.
        String sql = "SELECT (SELECT count(*) FROM pg_replication_slots) AS used,"
                + " current_setting('max_replication_slots')::int AS max_slots";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_SLOT_HEADROOM, false,
                        "could not read the replication slot quota");
            }
            int used = rs.getInt(1);
            int max = rs.getInt(2);
            if (used >= max) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_SLOT_HEADROOM, false,
                        "no free replication slot (" + used + "/" + max + " used) — raise"
                                + " max_replication_slots in postgresql.conf and restart the"
                                + " server, or drop unused slots: SELECT"
                                + " pg_drop_replication_slot('<name>')");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_SLOT_HEADROOM, true,
                    used + "/" + max + " replication slots in use");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_SLOT_HEADROOM, false, msg(e));
        }
    }

    // —— ⑤ test_decoding plugin available ——

    private CdcProbeResult.Check checkPluginAvailable(Connection conn) {
        // Probe by creating a throwaway slot with the test_decoding plugin and dropping it
        // immediately. A missing plugin surfaces as an SQL error; we convert it into a
        // failed check with remediation text instead of throwing.
        String slotName = "jync_probe_slot_" + System.currentTimeMillis();
        // P2-10: invariant guard — the generated name must satisfy the same PG
        // whitelist the consumer path enforces. It holds by construction today
        // (constant prefix + digits); if that ever changes, fail here, not at PG.
        if (!PgLogicalCdcConsumer.isSafeSlotName(slotName)) {
            throw new IllegalStateException("probe slot name outside PG whitelist: " + slotName);
        }
        boolean created = false;
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT pg_create_logical_replication_slot('" + slotName
                    + "', 'test_decoding')");
            created = true;
            st.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
            return CdcProbeResult.Check.of(CdcProbeResult.PG_PLUGIN_AVAILABLE, true,
                    "test_decoding reachable (probe slot created + dropped)"
                            + slotLogHint(st));
        } catch (SQLException e) {
            // P1-9(I5): only a slot that WAS created can leak. If the cleanup drop also
            // fails, the slot occupies one max_replication_slots quota and pins WAL until
            // a DBA removes it — name it in a WARN so it is actually cleanable. A create
            // failure must NOT warn (no slot ever existed).
            if (created) {
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
                } catch (SQLException dropFailure) {
                    log.warn("probe slot {} LEAKED (drop failed: {}) — it pins WAL and one"
                            + " max_replication_slots quota until removed manually with:"
                            + " SELECT pg_drop_replication_slot('{}')",
                            slotName, dropFailure.getMessage(), slotName);
                }
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_PLUGIN_AVAILABLE, false,
                    msg(e) + " — test_decoding is shipped with vanilla PG ≥ 9.4; if it"
                            + " was disabled, ensure shared_preload_libraries includes"
                            + " 'test_decoding' or use a custom output plugin");
        }
    }

    // —— ⑥ per-table REPLICA IDENTITY ——

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
                // pg_class.relreplident 官方语义:'d'=DEFAULT(主键作 old key)、'f'=FULL
                // (全行旧镜像)、'i'=INDEX(指定唯一索引列作 old key,足以定位原行)、
                // 'n'=NOTHING(UPDATE/DELETE 事件不带任何旧键 — 消费端无法定位原行,
                // upsert 残留旧行、delete 落空,必须拒绝)。历史上此处把 i/n 写反:
                // 误拒可用的 INDEX、放行不可用的 NOTHING。
                if (ident == null || "n".equals(ident)) {
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
