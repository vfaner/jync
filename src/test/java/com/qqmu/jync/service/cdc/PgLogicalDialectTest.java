package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PgLogicalDialectTest {

    @Test
    void detectsOpenGaussFromVersionBanner() {
        assertThat(PgLogicalDialect.detect("(openGauss 5.0.0 build) compiled at 2023-04-18"))
                .isEqualTo(PgLogicalDialect.OPENGAUSS);
    }

    @Test
    void detectsOpenGaussCaseInsensitively() {
        assertThat(PgLogicalDialect.detect("OpenGauss 6.0.0"))
                .isEqualTo(PgLogicalDialect.OPENGAUSS);
    }

    @Test
    void detectsVanillaPostgres() {
        assertThat(PgLogicalDialect.detect("PostgreSQL 16.4"))
                .isEqualTo(PgLogicalDialect.POSTGRES);
    }

    @Test
    void unknownOrMissingProductNameFallsBackToPostgres() {
        assertThat(PgLogicalDialect.detect(null)).isEqualTo(PgLogicalDialect.POSTGRES);
        assertThat(PgLogicalDialect.detect("")).isEqualTo(PgLogicalDialect.POSTGRES);
        assertThat(PgLogicalDialect.detect("HighGo Database 6.0"))
                .isEqualTo(PgLogicalDialect.POSTGRES);
    }

    @Test
    void postgresSqlIsTheExistingConstantByteForByte() {
        assertThat(PgLogicalDialect.POSTGRES.peekChangesSql())
                .isEqualTo("SELECT lsn::text, data FROM pg_logical_slot_peek_changes("
                        + "?::name, NULL::pg_lsn, ?::integer,"
                        + " VARIADIC ARRAY['include-xids', '1']::text[])");
        assertThat(PgLogicalDialect.POSTGRES.advanceSlotSql())
                .isEqualTo("SELECT end_lsn::text FROM pg_replication_slot_advance("
                        + "?::name, ?::pg_lsn)");
    }

    @Test
    void openGaussSqlDropsPgLsnAndRenamesLsnColumn() {
        assertThat(PgLogicalDialect.OPENGAUSS.peekChangesSql())
                .contains("location::text")
                .doesNotContain("::pg_lsn")
                .doesNotContain("?::name");
        assertThat(PgLogicalDialect.OPENGAUSS.advanceSlotSql())
                .doesNotContain("::pg_lsn")
                .doesNotContain("?::name");
    }

    @Test
    void onlyVanillaPostgresRequiresTheReplicationAttribute() {
        assertThat(PgLogicalDialect.POSTGRES.requiresReplicationRole()).isTrue();
        assertThat(PgLogicalDialect.OPENGAUSS.requiresReplicationRole()).isFalse();
    }
}
