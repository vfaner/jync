package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Vastbase G100 parses PostgreSQL's {@code ON CONFLICT … DO UPDATE} but rejects the
 * trailing {@code WHERE} used as the unchanged-row guard. A user report showed every
 * batch failing on that clause when the target was Vastbase, so the dialect must keep the
 * conflict form the server accepted and never emit the guard.
 */
class VastbaseDialectTest {

    private final PostgresDialect postgres = new PostgresDialect();
    private final VastbaseDialect vastbase = new VastbaseDialect();
    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect(), postgres, new OpenGaussDialect(), vastbase,
                    new OracleDialect(), new SqlServerDialect(), new Db2Dialect()),
            new GenericSqlDialect());

    @Test
    void factoryPicksTheVastbaseDialectNotPlainPostgres() {
        assertThat(factory.forType(DatabaseType.VASTBASE)).isSameAs(vastbase);
        assertThat(factory.forType(DatabaseType.POSTGRESQL)).isSameAs(postgres);
    }

    @Test
    void staysInThePostgresFamily() {
        assertThat(vastbase.family()).isEqualTo(DatabaseType.DialectFamily.POSTGRES);
    }

    @Test
    void nativeStatementCannotGuardButIncrementalWindowsAreEmulated() {
        // The native ON CONFLICT form must not receive the unsupported WHERE …
        assertThat(vastbase.supportsUpsertGuard()).isFalse();
        // … so a guarded incremental window is written UPDATE-then-INSERT instead.
        assertThat(vastbase.emulatesUpsertGuard()).isTrue();
        assertThat(postgres.supportsUpsertGuard()).isTrue();
        assertThat(postgres.emulatesUpsertGuard()).isFalse();
    }

    @Test
    void emulatedUpdateCarriesTheNullSafeGuardAndTheProbeIsKeyed() {
        String update = vastbase.getUpdateByPkSql("public", "orders",
                List.of("id", "name", "amount"), List.of("id"), true);
        assertThat(update).startsWith("UPDATE \"public\".\"orders\" SET")
                .contains("WHERE \"id\" = ?")
                .contains("\"name\" <> ?")
                .contains("\"name\" IS NULL AND ? IS NOT NULL");

        assertThat(vastbase.getSelectByPkSql("public", "orders", List.of("id")))
                .isEqualTo("SELECT 1 FROM \"public\".\"orders\" WHERE \"id\" = ?");
    }

    @Test
    void guardedUpsertKeepsOnConflictButDropsTheUnsupportedWhere() {
        Map<String, Integer> types = Map.of(
                "id", Types.INTEGER, "name", Types.VARCHAR, "amount", Types.INTEGER);

        String sql = vastbase.getUpsertSql(
                "aqsc_sjgl", "SJGL_STAFF_ZY", List.of("id", "name", "amount"), List.of("id"),
                types, true);

        assertThat(sql).contains("ON CONFLICT (\"id\") DO UPDATE SET")
                .doesNotContain(" WHERE ")
                .doesNotContain("ON DUPLICATE KEY");
        assertThat(sql).contains("\"name\" = EXCLUDED.\"name\"")
                .contains("\"amount\" = EXCLUDED.\"amount\"");
    }

    @Test
    void keyOnlyTableUpsertIsStillIdempotent() {
        String sql = vastbase.getUpsertSql("public", "kv", List.of("id"), List.of("id"));
        assertThat(sql).contains("ON CONFLICT (\"id\") DO NOTHING")
                .doesNotContain(" WHERE ");
    }

    @Test
    void tableWithoutPrimaryKeyGetsPlainInsert() {
        String sql = vastbase.getUpsertSql(
                "public", "logs", List.of("id", "msg"), List.of());
        assertThat(sql).doesNotContain("ON CONFLICT")
                .doesNotContain("ON DUPLICATE KEY");
    }

    @Test
    void timeBindsKeepThePostgresFamilyCasts() {
        Map<String, Integer> types = Map.of(
                "id", Types.INTEGER, "t", Types.TIME, "ts", Types.TIMESTAMP);

        String sql = vastbase.getUpsertSql(
                "public", "ev", List.of("id", "t", "ts"), List.of("id"), types);
        assertThat(sql).contains("(?, ?::time, ?::timestamp)");
    }
}
