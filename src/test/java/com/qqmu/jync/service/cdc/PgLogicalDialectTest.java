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

    /**
     * 真库实测:org.opengauss.Driver 6.0.0-og 的 {@code getDatabaseProductName()} 返回
     * <b>"PostgreSQL"</b>(version "9.2.4"),产品名对 openGauss 完全不具区分力。可用的
     * 证据只有 {@code SELECT version()} 的横幅,因此方言判定必须同时看它。
     */
    @Test
    void openGaussIsRecognisedFromSelectVersionWhenTheProductNameSaysPostgreSQL() {
        assertThat(PgLogicalDialect.detect("PostgreSQL",
                "(openGauss 5.0.0 build ) compiled at 2023-04-18 10:27:05 commit 0 last mr"
                        + " on aarch64-unknown-linux-gnu, compiled by g++ (GCC) 7.3.0, 64-bit"))
                .isEqualTo(PgLogicalDialect.OPENGAUSS);
    }

    @Test
    void vanillaPostgresBannerKeepsThePostgresDialect() {
        assertThat(PgLogicalDialect.detect("PostgreSQL",
                "PostgreSQL 16.4 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 13.2.0"))
                .isEqualTo(PgLogicalDialect.POSTGRES);
        assertThat(PgLogicalDialect.detect("PostgreSQL", null))
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

    /**
     * openGauss 没有 {@code pg_lsn},{@code location} 取代 {@code lsn}。但参数<b>必须</b>带
     * 显式强转:驱动把字符串传成 {@code character varying}、把 {@code setLong} 传成
     * {@code bigint},而 openGauss 的函数解析不接受这两个到 {@code name} / {@code integer}
     * 的隐式转换(实测报 {@code function pg_logical_slot_peek_changes(character varying,
     * unknown, bigint, text[]) does not exist})。原生 PG 有这些隐式转换,所以 POSTGRES
     * 分支的 SQL 一直带着强转,openGauss 分支同样需要。
     */
    @Test
    void openGaussSqlDropsPgLsnAndRenamesLsnColumn() {
        assertThat(PgLogicalDialect.OPENGAUSS.peekChangesSql())
                .contains("location::text")
                .contains("?::name")
                .contains("?::integer")
                .doesNotContain("::pg_lsn");
        assertThat(PgLogicalDialect.OPENGAUSS.advanceSlotSql())
                .isEqualTo("SELECT end_lsn::text FROM pg_replication_slot_advance("
                        + "?::name, ?::text)")
                .doesNotContain("::pg_lsn");
    }

    @Test
    void onlyVanillaPostgresRequiresTheReplicationAttribute() {
        assertThat(PgLogicalDialect.POSTGRES.requiresReplicationRole()).isTrue();
        assertThat(PgLogicalDialect.OPENGAUSS.requiresReplicationRole()).isFalse();
    }
}
