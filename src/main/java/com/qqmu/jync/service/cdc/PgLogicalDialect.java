package com.qqmu.jync.service.cdc;

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
            true),

    /**
     * openGauss 5.0.0, measured on {@code opengauss/opengauss:5.0.0}: the slot view
     * column is {@code location}, the {@code pg_lsn} type does not exist at all, and a
     * plain text parameter binds without casts.
     */
    OPENGAUSS(
            "SELECT location::text, data FROM pg_logical_slot_peek_changes("
                    + "?, NULL, ?, VARIADIC ARRAY['include-xids', '1']::text[])",
            "SELECT end_lsn::text FROM pg_replication_slot_advance(?, ?)",
            false);

    private final String peekChangesSql;
    private final String advanceSlotSql;
    private final boolean requiresReplicationRole;

    PgLogicalDialect(String peekChangesSql, String advanceSlotSql,
                     boolean requiresReplicationRole) {
        this.peekChangesSql = peekChangesSql;
        this.advanceSlotSql = advanceSlotSql;
        this.requiresReplicationRole = requiresReplicationRole;
    }

    static PgLogicalDialect detect(String productName) {
        if (productName != null
                && productName.toLowerCase(Locale.ROOT).contains("opengauss")) {
            return OPENGAUSS;
        }
        return POSTGRES;
    }

    String peekChangesSql() {
        return peekChangesSql;
    }

    String advanceSlotSql() {
        return advanceSlotSql;
    }

    boolean requiresReplicationRole() {
        return requiresReplicationRole;
    }
}
