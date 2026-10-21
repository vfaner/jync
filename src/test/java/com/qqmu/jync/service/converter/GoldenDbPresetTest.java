package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * ZTE GoldenDB speaks the MySQL protocol and its vendor documentation connects it with the
 * standard MySQL Connector/J ({@code com.mysql.cj.jdbc.Driver}, {@code jdbc:mysql://}). The
 * preset therefore reuses the bundled driver — no external jar — and everything else is the
 * MySQL family: dialect, metadata reader, type mapping.
 */
class GoldenDbPresetTest {

    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect(), new PostgresDialect(), new OpenGaussDialect(),
                    new GaussDbDialect(), new OracleDialect(), new SqlServerDialect(),
                    new Db2Dialect()),
            new GenericSqlDialect());

    @Test
    void presetReusesTheBundledMysqlDriverAndProtocol() {
        assertThat(DatabaseType.GOLDENDB.getDriverClassName())
                .isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(DatabaseType.GOLDENDB.getUrlTemplate()).startsWith("jdbc:mysql://");
        assertThat(DatabaseType.GOLDENDB.getDefaultPort()).isEqualTo(8880);
        assertThat(DatabaseType.GOLDENDB.getFamily()).isEqualTo(DatabaseType.DialectFamily.MYSQL);
    }

    @Test
    void goldenDbTargetsGetTheMysqlDialect() {
        assertThat(factory.forType(DatabaseType.GOLDENDB))
                .isInstanceOf(MySqlDialect.class);
    }

    @Test
    void mysqlUrlQualitiesAreKept() {
        // GoldenDB goes through the same Connector/J, so the two flags the sync depends on
        // must be present: affected-rows semantics for change counting, and batch rewriting
        // so a full load is not one round trip per row.
        String url = String.format(DatabaseType.GOLDENDB.getUrlTemplate(), "h", 8880, "db");
        assertThat(url).contains("useAffectedRows=true")
                .contains("rewriteBatchedStatements=true");
    }
}
