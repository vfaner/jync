package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * openGauss 5.0 ships in MySQL-compatibility sql_compatibility='A' (the factory default),
 * where the parser rejects PostgreSQL's {@code INSERT … ON CONFLICT} with
 * "syntax error at or near CONFLICT" but accepts the MySQL-flavoured
 * {@code INSERT … ON DUPLICATE KEY UPDATE} — verified against a real
 * opengauss/opengauss:5.0.0 container during the v1.7.1 docker matrix. The dialect stays
 * in the POSTGRES family for everything else (identifiers, functions, type mapping);
 * only the upsert form differs.
 */
class OpenGaussDialectTest {

    private final PostgresDialect postgres = new PostgresDialect();
    private final OpenGaussDialect openGauss = new OpenGaussDialect();
    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect(), postgres, openGauss, new OracleDialect(),
                    new SqlServerDialect(), new Db2Dialect()),
            new GenericSqlDialect());

    @Test
    void factoryPicksTheOpenGaussDialectNotPlainPostgres() {
        assertThat(factory.forType(DatabaseType.OPENGAUSS)).isSameAs(openGauss);
        // The other POSTGRES-family products keep the standard ON CONFLICT form.
        assertThat(factory.forType(DatabaseType.POSTGRESQL)).isSameAs(postgres);
    }

    @Test
    void staysInThePostgresFamily() {
        assertThat(openGauss.family()).isEqualTo(DatabaseType.DialectFamily.POSTGRES);
    }

    @Test
    void upsertUsesOnDuplicateKeyFormAcceptedByCompatibilityA() {
        String sql = openGauss.getUpsertSql(
                "public", "orders", List.of("id", "name", "amount"), List.of("id"));
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("ON CONFLICT");
        assertThat(sql).contains("\"name\" = VALUES(\"name\")")
                .contains("\"amount\" = VALUES(\"amount\")");
    }

    @Test
    void keyOnlyTableUpsertIsStillIdempotent() {
        String sql = openGauss.getUpsertSql("public", "kv", List.of("id"), List.of("id"));
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("ON CONFLICT");
    }

    @Test
    void tableWithoutPrimaryKeyGetsPlainInsert() {
        String sql = openGauss.getUpsertSql(
                "public", "logs", List.of("id", "msg"), List.of());
        assertThat(sql).doesNotContain("ON CONFLICT")
                .doesNotContain("ON DUPLICATE KEY");
    }

    @Test
    void timeBindsGetAnExplicitCastOnPostgresFamilyTargets() {
        // MySQL TIME rows are read/bound as strings (they can exceed 24h); openGauss and
        // PostgreSQL reject varchar→time without an explicit cast, which parked the whole
        // baseline in the v1.7.1 docker matrix ("expression is of type character varying").
        Map<String, Integer> types = Map.of(
                "id", Types.INTEGER, "t", Types.TIME, "ts", Types.TIMESTAMP);

        String pgSql = postgres.getUpsertSql(
                "public", "ev", List.of("id", "t", "ts"), List.of("id"), types);
        assertThat(pgSql).contains("(?, ?::time, ?::timestamp)");
        String ogSql = openGauss.getUpsertSql(
                "public", "ev", List.of("id", "t", "ts"), List.of("id"), types);
        assertThat(ogSql).contains("(?, ?::time, ?::timestamp)")
                .contains("ON DUPLICATE KEY UPDATE");

        // No type map (legacy callers): plain question marks, behaviour unchanged.
        assertThat(postgres.getUpsertSql(
                "public", "ev", List.of("id", "t"), List.of("id")))
                .contains("VALUES (?, ?)");
    }
}
