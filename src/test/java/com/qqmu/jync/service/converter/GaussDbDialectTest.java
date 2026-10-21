package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Huawei GaussDB is its own product, not openGauss under another name: it has a different
 * driver ({@code com.huawei.gaussdb.jdbc.Driver}, URL prefix {@code jdbc:gaussdb:}) and is
 * configured from a vendor jar. Its SQL, however, belongs to the PostgreSQL family, and its
 * parser accepts the MySQL-flavoured upsert that a default openGauss instance requires.
 *
 * <p>Without the preset the only way to connect was the CUSTOM type, which lands in the
 * GENERIC dialect family: no PostgreSQL type mapping (jsonb / uuid / arrays / domains reach
 * a MySQL target as raw names) and no PostgreSQL metadata reader.
 */
class GaussDbDialectTest {

    private final PostgresDialect postgres = new PostgresDialect();
    private final OpenGaussDialect openGauss = new OpenGaussDialect();
    private final GaussDbDialect gaussDb = new GaussDbDialect();
    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect(), postgres, openGauss, gaussDb, new OracleDialect(),
                    new SqlServerDialect(), new Db2Dialect()),
            new GenericSqlDialect());

    @Test
    void factoryPicksTheGaussDbDialectNotPlainPostgres() {
        assertThat(factory.forType(DatabaseType.GAUSSDB)).isSameAs(gaussDb);
        // openGauss keeps its own dialect; plain PostgreSQL keeps ON CONFLICT.
        assertThat(factory.forType(DatabaseType.OPENGAUSS)).isSameAs(openGauss);
        assertThat(factory.forType(DatabaseType.POSTGRESQL)).isSameAs(postgres);
    }

    @Test
    void presetCarriesTheVendorDriverUrlAndPort() {
        assertThat(DatabaseType.GAUSSDB.getDriverClassName())
                .isEqualTo("com.huawei.gaussdb.jdbc.Driver");
        assertThat(DatabaseType.GAUSSDB.getUrlTemplate()).isEqualTo("jdbc:gaussdb://%s:%d/%s");
        assertThat(DatabaseType.GAUSSDB.getDefaultPort()).isEqualTo(8000);
        assertThat(DatabaseType.GAUSSDB.getFamily()).isEqualTo(DatabaseType.DialectFamily.POSTGRES);
    }

    @Test
    void upsertUsesTheFormGaussDbAccepts() {
        String sql = gaussDb.getUpsertSql(
                "public", "orders", List.of("id", "name", "amount"), List.of("id"));
        // GaussDB refuses to update key columns in ON DUPLICATE KEY UPDATE; the inherited
        // builder only assigns non-key columns, so the statement stays within the limit.
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("ON CONFLICT")
                .doesNotContain("\"id\" = VALUES(\"id\")");
        assertThat(sql).contains("\"name\" = VALUES(\"name\")")
                .contains("\"amount\" = VALUES(\"amount\")");
    }
}
