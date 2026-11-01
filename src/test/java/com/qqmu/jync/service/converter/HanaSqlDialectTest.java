package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * SAP HANA rejects three shapes the generic dialect emits: the SQL:2008 pagination tail,
 * the ANSI column-alter forms and a plain INSERT where an idempotent upsert is required.
 * Every assertion here is a statement verified against HANA Express 2.00.082.
 */
class HanaSqlDialectTest {

    private final HanaSqlDialect dialect = new HanaSqlDialect();

    @Test
    void declaresTheHanaFamily() {
        assertThat(dialect.family()).isEqualTo(DatabaseType.DialectFamily.HANA);
    }

    @Test
    void identifiersAreDoubleQuotedAndCaseIsPreserved() {
        assertThat(dialect.qualify("JC_PROBE", "T_GEN2")).isEqualTo("\"JC_PROBE\".\"T_GEN2\"");
        assertThat(dialect.quoteIdentifier("MixEd")).isEqualTo("\"MixEd\"");
    }

    @Test
    void paginationUsesLimitOffset() {
        assertThat(dialect.getPaginationSql(
                "SELECT \"ID\" FROM \"JC_PROBE\".\"T\" ORDER BY \"ID\"", 20, 10))
                .isEqualTo("SELECT \"ID\" FROM \"JC_PROBE\".\"T\" ORDER BY \"ID\" LIMIT 10 OFFSET 20");
    }

    @Test
    void aKeylessTableFallsBackToAPlainInsert() {
        // WITH PRIMARY KEY is rejected by HANA when the target has no primary key, so the
        // keyless caller (which has no idempotent option anyway) must get a plain INSERT.
        assertThat(dialect.getUpsertSql("JC_PROBE", "T", List.of("ID", "NAME"), List.of()))
                .isEqualTo("INSERT INTO \"JC_PROBE\".\"T\" (\"ID\", \"NAME\") VALUES (?, ?)");
    }

    @Test
    void theFactoryRoutesHanaAndAHanaDeclaredCustomConnectionToThisDialect() {
        SqlDialectFactory factory = new SqlDialectFactory(
                List.of(new HanaSqlDialect(), new GenericSqlDialect()), new GenericSqlDialect());

        assertThat(factory.forType(DatabaseType.HANA)).isInstanceOf(HanaSqlDialect.class);
        assertThat(factory.forType(DatabaseType.CUSTOM, null,
                DatabaseType.DialectFamily.HANA)).isInstanceOf(HanaSqlDialect.class);
    }
}
