package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * 神通 (OSCAR) keeps the PostgreSQL catalog and type mapping but is Oracle-compatible in
 * DML: vendor migration guidance recommends {@code MERGE INTO}, and PostgreSQL's
 * {@code INSERT … ON CONFLICT} is not documented. The dialect must never emit it.
 */
class OscarDialectTest {

    private final PostgresDialect postgres = new PostgresDialect();
    private final OscarDialect oscar = new OscarDialect();
    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect(), postgres, new OpenGaussDialect(), oscar,
                    new OracleDialect(), new SqlServerDialect(), new Db2Dialect()),
            new GenericSqlDialect());

    @Test
    void factoryPicksTheOscarDialectNotPlainPostgres() {
        assertThat(factory.forType(DatabaseType.OSCAR)).isSameAs(oscar);
        assertThat(factory.forType(DatabaseType.POSTGRESQL)).isSameAs(postgres);
    }

    @Test
    void staysInThePostgresFamily() {
        assertThat(oscar.family()).isEqualTo(DatabaseType.DialectFamily.POSTGRES);
    }

    @Test
    void upsertUsesOracleStyleMergeAndNeverOnConflict() {
        String sql = oscar.getUpsertSql(
                "APP", "ORDERS", List.of("ID", "NAME", "AMOUNT"), List.of("ID"));
        assertThat(sql).startsWith("MERGE INTO \"APP\".\"ORDERS\" t USING (SELECT")
                .contains("? AS \"ID\"")
                .contains("FROM DUAL")
                .contains("WHEN MATCHED THEN UPDATE SET t.\"NAME\" = s.\"NAME\"")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("ON CONFLICT")
                .doesNotContain("DUPLICATE KEY")
                .doesNotContain(" WHERE ");
    }

    @Test
    void guardFlagDoesNotAddAWhereBranch() {
        Map<String, Integer> types = Map.of(
                "ID", Types.INTEGER, "NAME", Types.VARCHAR);
        String sql = oscar.getUpsertSql(
                "APP", "ORDERS", List.of("ID", "NAME"), List.of("ID"), types, true);
        assertThat(sql).contains("WHEN MATCHED THEN UPDATE SET")
                .doesNotContain(" WHERE ")
                .doesNotContain("ON CONFLICT");
    }

    @Test
    void keyOnlyTableStillGetsAValidMerge() {
        String sql = oscar.getUpsertSql("APP", "KV", List.of("ID"), List.of("ID"));
        assertThat(sql).contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("WHEN MATCHED");
    }

    @Test
    void tableWithoutPrimaryKeyGetsPlainInsert() {
        String sql = oscar.getUpsertSql("APP", "LOGS", List.of("ID", "MSG"), List.of());
        assertThat(sql).startsWith("INSERT INTO")
                .doesNotContain("MERGE")
                .doesNotContain("ON CONFLICT");
    }

    @Test
    void skipsTheGuardAndTypesTemporalBinds() {
        // The MERGE projection has no ::timestamp cast and Oracle-style NLS conversion
        // rejects the wall-clock text MySQL sources produce, so the binder must parse it.
        assertThat(oscar.supportsUpsertGuard()).isFalse();
        assertThat(oscar.requiresTypedTemporalBind()).isTrue();
    }
}
