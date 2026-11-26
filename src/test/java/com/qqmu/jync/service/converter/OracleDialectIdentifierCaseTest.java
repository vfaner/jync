package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OracleDialectIdentifierCaseTest {

    @Test
    void theDefaultFoldsBareIdentifiersToUpperCase() {
        assertThat(new OracleDialect().quoteIdentifier("user_info")).isEqualTo("\"USER_INFO\"");
    }

    @Test
    void preserveModeQuotesTheSourceSpelling() {
        SqlDialect dialect = new OracleDialect().withPreserveIdentifierCase(true);
        assertThat(dialect.quoteIdentifier("user_info")).isEqualTo("\"user_info\"");
        assertThat(dialect.quoteIdentifier("MiXeD")).isEqualTo("\"MiXeD\"");
    }
}
