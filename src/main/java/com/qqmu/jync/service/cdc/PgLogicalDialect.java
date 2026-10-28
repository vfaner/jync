package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Server-side dialect for {@code PG_LOGICAL_CDC}. Selected from what the server can
 * actually do — never from the preset the user picked, because a "PostgreSQL" preset can
 * point at an openGauss instance.
 *
 * <p><b>Capability, not brand (v1.9.0).</b> The domestic PG-family kernels do not carry
 * their own CDC mechanism: each is PostgreSQL logical decoding on top of whatever PG
 * release it forked, and the SQL surface differs by one catalog fact — whether the
 * {@code pg_lsn} type exists. With it: the {@code lsn} column and the {@code ::pg_lsn}
 * casts (PG 9.4+). Without it: the pre-9.4 {@code location} column and plain casts
 * (measured on openGauss 5.0.0, a 9.2 fork). Measured: postgres:16.15 → present,
 * openGauss 5.0.0 → absent. Asking the catalog is therefore both more accurate and more
 * general than matching product names — a kernel nobody has tested yet lands on the
 * correct surface by what it supports, not by what it calls itself. The name/banner check
 * survives only as the fallback for a server whose catalog cannot be read.
 *
 * <p>Why the WAL-position function name is <b>not</b> a third dialect: only PostgreSQL
 * 9.4–9.6 combines {@code pg_current_xlog_location()} with a {@code pg_lsn} type, and
 * those servers also lack {@code pg_replication_slot_advance()} (PostgreSQL 11+). A
 * pre-10 branch would anchor the baseline, load, stream — then fail forever on the first
 * position advance, which is strictly worse than failing loudly at the baseline anchor.
 * They stay on POSTGRES.
 */
enum PgLogicalDialect {

    /**
     * Vanilla PostgreSQL. The two SQL strings are byte-for-byte the constants v1.7.0
     * shipped; keep them that way or native PostgreSQL behavior changes with no test
     * able to tell.
     *
     * <p>Why they look the way they do (F-11): reads use PEEK, never {@code get_changes}
     * — peeking is non-consuming, so a crash mid-apply replays the transaction instead of
     * losing it. The options argument is {@code VARIADIC text[]}, which a
     * PreparedStatement cannot bind directly (a bound text parameter does not coerce to
     * {@code text[]}); the option names/values are internal constants, so inline-quoting
     * them is injection-safe. The slot name needs an explicit {@code ::name} cast — the
     * driver binds it as varchar and PG does not implicitly coerce varchar to name once
     * the call also carries a VARIADIC array (overload resolution fails). Likewise the
     * advance parameter is {@code ::pg_lsn}. Advancing to the transaction's COMMIT LSN is
     * exactly right: {@code confirmed_flush} is exclusive, and the next transaction's
     * BEGIN may carry that same LSN and is still delivered.
     *
     * <p>{@code max_changes} is a hint, not a hard cut: logical decoding never emits half
     * a transaction — if the next complete transaction would exceed the limit, only
     * already-decoded transactions are returned (empirically, limit=2 still returned a
     * 7-row transaction). Hence every peeked transaction is flushable as a whole.
     */
    POSTGRES(
            "SELECT lsn::text, data FROM pg_logical_slot_peek_changes("
                    + "?::name, NULL::pg_lsn, ?::integer,"
                    + " VARIADIC ARRAY['include-xids', '1']::text[])",
            "SELECT end_lsn::text FROM pg_replication_slot_advance("
                    + "?::name, ?::pg_lsn)",
            "SELECT pg_current_wal_lsn()::text",
            true /* has pg_lsn */),

    /**
     * openGauss 5.0.0, measured on {@code opengauss/opengauss:5.0.0}: the slot view
     * column is {@code location} and the {@code pg_lsn} type does not exist at all.
     *
     * <p>The parameter casts are NOT optional here. The driver sends a bound string as
     * {@code character varying} and {@code setLong} as {@code bigint}; openGauss resolves
     * functions strictly and has no implicit cast from those to the declared
     * {@code name} / {@code integer} / {@code text}, so the cast-less form fails with
     * {@code function pg_logical_slot_peek_changes(character varying, unknown, bigint,
     * text[]) does not exist} (reproduced against the real server). Native PostgreSQL has
     * those implicit casts, which is why the POSTGRES branch always carried them.
     */
    OPENGAUSS(
            "SELECT location::text, data FROM pg_logical_slot_peek_changes("
                    + "?::name, NULL::text, ?::integer,"
                    + " VARIADIC ARRAY['include-xids', '1']::text[])",
            "SELECT end_lsn::text FROM pg_replication_slot_advance(?::name, ?::text)",
            // openGauss forked PG 9.2: the WAL-position function keeps its pre-10 name.
            "SELECT pg_current_xlog_location()::text",
            false /* no pg_lsn type at all */);

    private final String peekChangesSql;
    private final String advanceSlotSql;
    private final String currentLsnSql;

    /**
     * Whether the server has the {@code pg_lsn} type — the single catalog fact that splits
     * the two dialects, and the one every derived capability below is built on.
     */
    private final boolean pgLsnType;

    PgLogicalDialect(String peekChangesSql, String advanceSlotSql, String currentLsnSql,
                     boolean pgLsnType) {
        this.peekChangesSql = peekChangesSql;
        this.advanceSlotSql = advanceSlotSql;
        this.currentLsnSql = currentLsnSql;
        this.pgLsnType = pgLsnType;
    }

    static PgLogicalDialect detect(String productName) {
        return detect(productName, null);
    }

    /**
     * The product name alone is not enough: measured on {@code opengauss/opengauss:5.0.0}
     * with the official driver (6.0.0-og), {@code getDatabaseProductName()} answers
     * <b>"PostgreSQL"</b> and {@code getDatabaseProductVersion()} "9.2.4". The only
     * reliable marker is the {@code SELECT version()} banner, which starts
     * {@code (openGauss 5.0.0 build )}. Either input naming openGauss wins; both absent
     * or unrecognised stays POSTGRES, the v1.7.0 behaviour.
     */
    static PgLogicalDialect detect(String productName, String versionText) {
        if (mentionsOpenGauss(productName) || mentionsOpenGauss(versionText)) {
            return OPENGAUSS;
        }
        return POSTGRES;
    }

    private static boolean mentionsOpenGauss(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("opengauss");
    }

    /**
     * Resolves the dialect from a live connection, asking the catalog what the server can
     * do (one round trip, one query, both facts). Callers ask once per connection, never
     * per poll.
     *
     * <p>When the catalog cannot be read — a non-PostgreSQL server behind a PG-family
     * preset, a locked-down catalog — fall back to the product name and the
     * {@code SELECT version()} banner, i.e. the v1.7.0–v1.8.1 behaviour, which keeps a
     * lying product name from costing a working connection. Anything unrecognised stays
     * POSTGRES. Never throws.
     */
    static PgLogicalDialect detectLive(Connection conn) {
        Boolean hasPgLsn = hasPgLsnType(conn);
        if (hasPgLsn != null) {
            return hasPgLsn ? POSTGRES : OPENGAUSS;
        }
        String product = null;
        try {
            DatabaseMetaData md = conn.getMetaData();
            product = md == null ? null : md.getDatabaseProductName();
        } catch (SQLException | RuntimeException ignored) {
            // no metadata → fall through to the version banner
        }
        if (mentionsOpenGauss(product)) {
            return OPENGAUSS;
        }
        return detect(product, versionText(conn));
    }

    /**
     * Catalog form, not {@code to_regtype}: that function arrived in PG 9.4, and the whole
     * point of this query is to work on the 9.2-lineage kernels that predate it.
     * {@code null} means "cannot tell", never "unsupported".
     */
    private static Boolean hasPgLsnType(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(CAPABILITY_SQL)) {
            return rs.next() ? rs.getLong(1) > 0 : null;
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    private static final String CAPABILITY_SQL =
            "SELECT count(*) FROM pg_catalog.pg_type WHERE typname = 'pg_lsn'";

    /** {@code SELECT version()} as text, or null when the server will not answer. */
    static String versionText(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    String peekChangesSql() {
        return peekChangesSql;
    }

    String advanceSlotSql() {
        return advanceSlotSql;
    }

    /** One-row, one-text-column query returning the current WAL position. */
    String currentLsnSql() {
        return currentLsnSql;
    }

    /**
     * Whether this server can report how much WAL a slot holds back — the status panel's
     * gauge, {@code pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)}. All three names
     * arrived together with the {@code pg_lsn} type (PostgreSQL 10 renamed them from
     * {@code pg_current_xlog_location}/{@code pg_xlog_location_diff}); openGauss, a 9.2
     * fork, has none of them, so the consumer skips the sample there instead of sending a
     * query that can never succeed every 30 seconds.
     */
    boolean canMeasureRetainedWal() {
        return pgLsnType;
    }
}
