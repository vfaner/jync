package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class PgLogicalDialectTest {

    // —— v1.9.0(能力判定):方言由"服务器有没有这个能力"决定,不由品牌名决定 ——

    /**
     * 目录回答:{@code pg_lsn} 类型存在与否。实测 postgres:16.15 有、openGauss 5.0.0 没有。
     * 这一条事实就决定整套 SQL 的形态(见 {@link PgLogicalDialect} 类注释)。
     */
    private static Connection capability(long hasPgLsn) throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(hasPgLsn);
        return conn;
    }

    @Test
    void catalogWithPgLsnIsNativePostgres() throws SQLException {
        assertThat(PgLogicalDialect.detectLive(capability(1)))
                .isEqualTo(PgLogicalDialect.POSTGRES);
    }

    @Test
    void catalogWithoutPgLsnIsTheOpenGaussSurface() throws SQLException {
        assertThat(PgLogicalDialect.detectLive(capability(0)))
                .isEqualTo(PgLogicalDialect.OPENGAUSS);
    }

    /** 目录读不到(权限/非 PG 服务器)时退回品牌名 + 横幅,即 v1.7.0–v1.9.0 的旧路径。 */
    @Test
    void unreadableCatalogFallsBackToTheVersionBanner() throws SQLException {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        // 目录查询失败,横幅查询成功 —— 两个独立 stub,不在 Answer 里再建/再 stub ，
        // 那样会打断 Mockito 的 ongoing stubbing,第二次查询拿不到结果。
        when(st.executeQuery(contains("pg_type")))
                .thenThrow(new SQLException("catalog is not readable"));
        ResultSet banner = mock(ResultSet.class);
        when(banner.next()).thenReturn(true, false);
        when(banner.getString(1)).thenReturn("(openGauss 5.0.0 build ) compiled at 2023-04-18");
        when(st.executeQuery("SELECT version()")).thenReturn(banner);

        assertThat(PgLogicalDialect.detectLive(conn)).isEqualTo(PgLogicalDialect.OPENGAUSS);
    }


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
