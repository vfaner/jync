package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * v1.7.0 PostgreSQL logical-replication readiness probe — the PG twin of
 * {@link LogMinerCdcProbe}.
 *
 * <p>Itemized, never throws: a merely-unready source degrades to failed checks with
 * remediation text a DBA can act on. The consumer reads a {@code test_decoding} slot
 * via JDBC ({@code pg_logical_slot_peek_changes}, then an explicit advance); the probe
 * creates that slot, runs both statements against it (⑤ — the only way to learn whether
 * this particular kernel's SQL surface matches), and checks that UPDATE/DELETE events will
 * carry enough keys to drive upserts (⑥).
 *
 * <p>Scope: the PostgreSQL family — PostgreSQL itself and the domestic kernels built on
 * PG logical decoding (openGauss / GaussDB / Kingbase / Highgo / Vastbase / Oscar /
 * RaseSQL / SeaBoxSQL). {@link CdcProbeResult#PG_SOURCE} accepts any server that reports
 * a product name; what actually decides "usable" are the checks below, and the SQL surface
 * is picked by capability in {@link PgLogicalDialect#detectLive}, never by brand. DM is
 * <b>not</b> in this family (Oracle-compatible; it has its own LogMiner-style capture).
 */
@Component
@Slf4j
public class PgLogicalReplicationCdcProbe {

    /**
     * @param conn        live source connection (already opened by the caller)
     * @param ownerTables each element is {@code [schema, table]} exactly as the project
     *                    stores it (a quoted mixed-case name is matched verbatim first and
     *                    only then by its lower-case folding) — the tables the project
     *                    intends to capture; may be empty (connection test before any
     *                    project is configured) in which case table-level checks are
     *                    skipped.
     */
    public CdcProbeResult probe(Connection conn, List<String[]> ownerTables) {
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        // Resolved once: identify() may cost a SELECT version() round trip, and ① and ③
        // read the same answer.
        PgLogicalDialect dialect = PgLogicalDialect.detectLive(conn);
        checks.add(checkPgSource(conn, dialect));
        checks.add(checkWalLevel(conn));
        checks.add(checkReplicationRole(conn, dialect));
        checks.add(checkSlotHeadroom(conn));
        checks.add(checkPluginAvailable(conn, dialect));
        if (ownerTables != null && !ownerTables.isEmpty()) {
            checks.add(checkReplicaIdentity(conn, ownerTables));
        }
        return CdcProbeResult.of(checks);
    }

    // —— ① PG family (v1.9.0: name no longer decides, capability does) ——

    private CdcProbeResult.Check checkPgSource(Connection conn, PgLogicalDialect dialect) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || product.isBlank()) {
                return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, false,
                        "the server did not report a product name — the PostgreSQL-family"
                                + " gate cannot be decided; check the JDBC URL and driver");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, true,
                    product + (dialect == PgLogicalDialect.OPENGAUSS
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

    private CdcProbeResult.Check checkReplicationRole(Connection conn,
                                                      PgLogicalDialect dialect) {
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
            if (dialect == PgLogicalDialect.OPENGAUSS) {
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
     * survive a server restart. Vanilla PostgreSQL has no such GUC at all, so asking
     * there is a guaranteed-failing round trip on every probe — the caller only asks
     * when the dialect is openGauss. The check's verdict never depends on the answer.
     */
    private static String slotLogHint(Statement st) {
        try (ResultSet rs = st.executeQuery("SHOW enable_slot_log")) {
            String value = rs.next() ? rs.getString(1) : null;
            if (value != null && "off".equalsIgnoreCase(value)) {
                return "; enable_slot_log=off — enable it so the slot survives a server"
                        + " restart (after a restart the position is rebuilt from a baseline)";
            }
        } catch (SQLException | RuntimeException ignored) {
            // an old openGauss build without the GUC — no hint to give
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

    private CdcProbeResult.Check checkPluginAvailable(Connection conn,
                                                      PgLogicalDialect dialect) {
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
            String position = verifySqlSurface(conn, dialect, slotName);
            st.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
            return CdcProbeResult.Check.of(CdcProbeResult.PG_PLUGIN_AVAILABLE, true,
                    "test_decoding reachable (probe slot created, read, advanced to "
                            + position + " and dropped)"
                            + (dialect == PgLogicalDialect.OPENGAUSS ? slotLogHint(st) : ""));
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
            String detail = msg(e);
            if (!detail.startsWith(SURFACE_FAILURE)) {
                // Only a genuine "the plugin is missing" failure earns this remediation.
                // A statement that ran and was refused is a different problem, and
                // blaming shared_preload_libraries there sends the DBA the wrong way.
                detail += " — test_decoding is shipped with vanilla PG ≥ 9.4; if it"
                        + " was disabled, ensure shared_preload_libraries includes"
                        + " 'test_decoding' or use a custom output plugin";
            }
            return CdcProbeResult.Check.of(CdcProbeResult.PG_PLUGIN_AVAILABLE, false, detail);
        }
    }

    /**
     * v1.9.0: runs the exact statements the consumer will run, against the throwaway slot —
     * the peek it reads changes with and the advance it moves the position with. "The slot
     * could be created" does not prove either: the domestic PG-family kernels are forks of
     * different PG releases, and their surface differs in ways only execution reveals (the
     * peek's {@code lsn} vs {@code location} column, the argument casts, whether
     * {@code pg_replication_slot_advance} — PostgreSQL 11+ — exists at all). Without this,
     * such a kernel passed the probe, loaded a whole baseline, and then failed forever at
     * the first position advance.
     *
     * @return the WAL position the probe slot was advanced to
     * @throws SQLException naming which statement the server refused; the caller converts it
     *                      into a failed check (and still drops the slot)
     */
    private static String verifySqlSurface(Connection conn, PgLogicalDialect dialect,
                                           String slotName) throws SQLException {
        try (java.sql.PreparedStatement peek = conn.prepareStatement(dialect.peekChangesSql())) {
            peek.setString(1, slotName);
            peek.setInt(2, 1);
            // A slot created a moment ago holds no transaction: an empty result is success.
            // Executing it at all proves the function signature and both column names.
            try (ResultSet rs = peek.executeQuery()) {
                while (rs.next()) {
                    rs.getString(2);
                }
            }
        } catch (SQLException | RuntimeException e) {
            throw surfaceFailure("the peek statement Jync reads changes with", e);
        }
        String position;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(dialect.currentLsnSql())) {
            position = rs.next() ? rs.getString(1) : null;
        } catch (SQLException | RuntimeException e) {
            throw surfaceFailure("the WAL-position query", e);
        }
        if (position == null || position.isBlank()) {
            throw surfaceFailure("the WAL-position query", null);
        }
        boolean advanced;
        try (java.sql.PreparedStatement advance =
                     conn.prepareStatement(dialect.advanceSlotSql())) {
            advance.setString(1, slotName);
            advance.setString(2, position);
            try (ResultSet rs = advance.executeQuery()) {
                advanced = rs.next();
            }
        } catch (SQLException | RuntimeException e) {
            throw surfaceFailure("the advance statement", e);
        }
        if (!advanced) {
            throw surfaceFailure("the advance statement", null);
        }
        return position;
    }

    /** Marks a failure as "the statement ran and was refused", not "the plugin is missing". */
    private static final String SURFACE_FAILURE =
            "Jync's capture statements do not run on this server";

    private static SQLException surfaceFailure(String statement, Exception cause) {
        String detail = cause == null ? statement + " returned no row"
                : statement + " failed: " + (cause instanceof SQLException sql
                        ? msg(sql) : String.valueOf(cause.getMessage()));
        return new SQLException(SURFACE_FAILURE + " — " + detail, cause);
    }

    // —— ⑥ per-table REPLICA IDENTITY ——

    /**
     * At most this many table names per catalog query. PostgreSQL allows 65535 bind
     * parameters; the cap keeps the statement small and the round trip short without
     * splitting a realistic selection (hundreds of tables) into more than one query.
     */
    private static final int IDENTITY_QUERY_BATCH = 500;

    /**
     * ⑥ per-table REPLICA IDENTITY, one catalog query per schema per batch.
     *
     * <p>Not one query per table: a project may select hundreds of tables (bulk creation
     * allows thousands), and this check also runs while the detail page of a brand-new
     * project renders. A round trip per table made an advisory check scale with the
     * selection.
     *
     * <p>Names are matched exactly first and by their SQL-unquoted folding second (see
     * {@link #identitiesByTable}); a table that the catalog does not hold at all is reported
     * separately from one whose identity is {@code NOTHING} — the two have different fixes
     * (re-check the selection vs. {@code ALTER TABLE … REPLICA IDENTITY}).
     */
    private CdcProbeResult.Check checkReplicaIdentity(Connection conn, List<String[]> ownerTables) {
        // schema -> table names, preserving selection order for a stable message.
        java.util.LinkedHashMap<String, List<String>> bySchema = new java.util.LinkedHashMap<>();
        for (String[] ot : ownerTables) {
            if (ot == null || ot.length < 2 || ot[1] == null || ot[1].isBlank()) {
                continue;
            }
            String schema = ot[0] == null || ot[0].isBlank() ? "public" : ot[0];
            bySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(ot[1]);
        }
        List<String> missing = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        int checked = 0;
        try {
            for (Map.Entry<String, List<String>> e : bySchema.entrySet()) {
                String schema = e.getKey();
                Map<String, String> identities = new java.util.HashMap<>();
                for (String variant : schemaVariants(schema)) {
                    identitiesByTable(conn, variant, e.getValue())
                            .forEach(identities::putIfAbsent);
                }
                for (String table : e.getValue()) {
                    // pg_class.relreplident 官方语义:'d'=DEFAULT(主键作 old key)、'f'=FULL
                    // (全行旧镜像)、'i'=INDEX(指定唯一索引列作 old key,足以定位原行)、
                    // 'n'=NOTHING(UPDATE/DELETE 事件不带任何旧键 — 消费端无法定位原行,
                    // upsert 残留旧行、delete 落空,必须拒绝)。历史上此处把 i/n 写反:
                    // 误拒可用的 INDEX、放行不可用的 NOTHING。
                    String ident = identities.get(table);
                    if (ident == null) {
                        ident = identities.get(table.toLowerCase(Locale.ROOT));
                    }
                    checked++;
                    if (ident == null) {
                        notFound.add(schema + "." + table);
                    } else if ("n".equals(ident)) {
                        missing.add(schema + "." + table);
                    }
                }
            }
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICA_IDENTITY, false,
                    msg(e));
        }
        if (missing.isEmpty() && notFound.isEmpty()) {
            return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICA_IDENTITY, true,
                    checked + " table(s) with usable REPLICA IDENTITY");
        }
        List<String> parts = new ArrayList<>(2);
        if (!missing.isEmpty()) {
            parts.add("REPLICA IDENTITY is NOTHING on: " + String.join(", ", missing)
                    + " — fix with: ALTER TABLE \"<schema>\".\"<table>\""
                    + " REPLICA IDENTITY DEFAULT;  -- or FULL if no PK");
        }
        if (!notFound.isEmpty()) {
            parts.add("not found in the source catalog: " + String.join(", ", notFound)
                    + " — the table may have been renamed or dropped, or the selection uses"
                    + " another schema (an exact name is tried first, then its lower-case"
                    + " folding)");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.PG_REPLICA_IDENTITY, false,
                String.join("; ", parts));
    }

    /** The schema as given, plus its SQL-unquoted folding when that differs. */
    private static List<String> schemaVariants(String schema) {
        String folded = schema.toLowerCase(Locale.ROOT);
        return folded.equals(schema) ? List.of(schema) : List.of(schema, folded);
    }

    /**
     * {@code relname -> relreplident} for the named tables of one schema, in batches.
     *
     * <p>Each name is looked up by its exact spelling first and by the SQL-unquoted folding
     * second. The catalog keeps a quoted identifier's real case ({@code "MyTable"}), and the
     * picker stores names verbatim, so folding unconditionally would report such a table
     * missing; asking only the exact form would report a name the caller typed in another
     * case. Both spellings travel in the same query, and resolution prefers the exact hit,
     * so a schema holding both {@code "MyTable"} and {@code mytable} still matches the
     * table the project actually selected.
     *
     * <p>{@code relreplident} is {@code char NOT NULL}: an absent key in the map means
     * "no such table", never "unknown identity".
     */
    private static Map<String, String> identitiesByTable(Connection conn, String schema,
                                                         List<String> tables)
            throws SQLException {
        // Every name to ask about, exact spelling first; keep order deterministic.
        java.util.LinkedHashSet<String> asked = new java.util.LinkedHashSet<>();
        for (String table : tables) {
            asked.add(table);
        }
        for (String table : tables) {
            asked.add(table.toLowerCase(Locale.ROOT));
        }
        List<String> names = new ArrayList<>(asked);
        Map<String, String> identities = new java.util.HashMap<>();
        for (int from = 0; from < names.size(); from += IDENTITY_QUERY_BATCH) {
            List<String> batch = names.subList(from,
                    Math.min(names.size(), from + IDENTITY_QUERY_BATCH));
            StringBuilder sql = new StringBuilder(
                    "SELECT c.relreplident, c.relname FROM pg_catalog.pg_class c"
                            + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                            + " WHERE n.nspname = ? AND c.relname IN (");
            for (int i = 0; i < batch.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            sql.append(')');
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                ps.setString(1, schema);
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 2, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        identities.put(rs.getString(2), rs.getString(1));
                    }
                }
            }
        }
        return identities;
    }

    private static String msg(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
