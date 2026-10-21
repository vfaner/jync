package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * A CUSTOM connection can declare the SQL family it speaks.
 *
 * <p>CUSTOM exists for products the app has no preset for. Without a declared family the app
 * can only emit ANSI-leaning generic SQL, which is wrong for most real products — a custom
 * MySQL-compatible database needs {@code ON DUPLICATE KEY UPDATE}, a custom Oracle-compatible
 * one needs {@code MERGE}. The override applies to CUSTOM only; a preset already knows its own
 * family, and a stale form value must not redirect it.
 */
class SqlDialectCustomFamilyTest {

    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new OracleDialect(), new PostgresDialect(), new MySqlDialect(),
                    new SqlServerDialect(), new GenericSqlDialect()),
            new GenericSqlDialect());

    private static final List<String> COLUMNS = List.of("ID", "NAME");
    private static final List<String> PK = List.of("ID");

    @Test
    void aCustomConnectionWithNoFamilyKeepsTheGenericDialect() {
        SqlDialect dialect = factory.forType(DatabaseType.CUSTOM, null, null);

        assertThat(dialect.family()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
    }

    @Test
    void aCustomConnectionCanDeclareThePostgresFamily() {
        SqlDialect dialect = factory.forType(DatabaseType.CUSTOM, null,
                DatabaseType.DialectFamily.POSTGRES);

        assertThat(dialect.family()).isEqualTo(DatabaseType.DialectFamily.POSTGRES);
        assertThat(dialect.getUpsertSql("public", "orders", COLUMNS, PK))
                .contains("ON CONFLICT");
    }

    @Test
    void aCustomConnectionCanDeclareTheMysqlFamily() {
        SqlDialect dialect = factory.forType(DatabaseType.CUSTOM, null,
                DatabaseType.DialectFamily.MYSQL);

        assertThat(dialect.family()).isEqualTo(DatabaseType.DialectFamily.MYSQL);
        assertThat(dialect.getUpsertSql("s", "orders", COLUMNS, PK))
                .contains("ON DUPLICATE KEY");
    }

    @Test
    void aCustomOracleFamilyGetsTheVersionAwareOracleGrammar() {
        SqlDialect eleven = factory.forType(DatabaseType.CUSTOM, 11,
                DatabaseType.DialectFamily.ORACLE);
        SqlDialect nineteen = factory.forType(DatabaseType.CUSTOM, 19,
                DatabaseType.DialectFamily.ORACLE);

        assertThat(eleven).isInstanceOf(OracleDialect.class);
        assertThat(eleven.maxIdentifierBytes()).isEqualTo(30);
        assertThat(nineteen.maxIdentifierBytes()).isEqualTo(128);
    }

    @Test
    void aCustomConnectionCanDeclareTheSqlServerFamily() {
        assertThat(factory.forType(DatabaseType.CUSTOM, null,
                DatabaseType.DialectFamily.SQLSERVER).family())
                .isEqualTo(DatabaseType.DialectFamily.SQLSERVER);
    }

    @Test
    void aPresetIgnoresTheOverride() {
        // A stale select value in a crafted POST must not turn a MySQL preset into Oracle SQL.
        assertThat(factory.forType(DatabaseType.MYSQL, null,
                DatabaseType.DialectFamily.ORACLE).family())
                .isEqualTo(DatabaseType.DialectFamily.MYSQL);
    }
}
