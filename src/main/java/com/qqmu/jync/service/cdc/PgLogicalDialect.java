package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Server-side dialect for {@code PG_LOGICAL_CDC}. Selected from the product name the
 * server actually reports — never from the preset the user picked, because a
 * "PostgreSQL" preset can point at an openGauss instance.
 *
 * <p>The POSTGRES SQL strings are byte-for-byte the constants v1.7.0 shipped; keep them
 * that way or native PostgreSQL behavior changes with no test able to tell.
 */
enum PgLogicalDialect {

    POSTGRES(
            "SELECT lsn::text, data FROM pg_logical_slot_peek_changes("
                    + "?::name, NULL::pg_lsn, ?::integer,"
                    + " VARIADIC ARRAY['include-xids', '1']::text[])",
            "SELECT end_lsn::text FROM pg_replication_slot_advance("
                    + "?::name, ?::pg_lsn)",
            "SELECT pg_current_wal_lsn()::text",
            true),

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
            false);

    private final String peekChangesSql;
    private final String advanceSlotSql;
    private final String currentLsnSql;
    private final boolean requiresReplicationRole;

    PgLogicalDialect(String peekChangesSql, String advanceSlotSql, String currentLsnSql,
                     boolean requiresReplicationRole) {
        this.peekChangesSql = peekChangesSql;
        this.advanceSlotSql = advanceSlotSql;
        this.currentLsnSql = currentLsnSql;
        this.requiresReplicationRole = requiresReplicationRole;
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
     * Resolves the dialect from a live connection. Cheap by construction: the extra
     * {@code SELECT version()} round trip only happens when the product name already
     * failed to name openGauss, and callers ask once per connection, never per poll.
     * Every failure degrades to POSTGRES rather than throwing.
     */
    static PgLogicalDialect detectLive(Connection conn) {
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

    boolean requiresReplicationRole() {
        return requiresReplicationRole;
    }
}
