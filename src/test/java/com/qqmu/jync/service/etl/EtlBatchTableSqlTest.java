package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;

/**
 * The default extraction SQL for a table picked in the batch form. It must quote with the
 * source's own dialect: an unquoted mixed-case Oracle name or a name containing a space would
 * otherwise fail on the first run, after the batch had already been created.
 */
class EtlBatchTableSqlTest {

    @Test
    void usesTheDialectsIdentifierQuotes() {
        assertThat(EtlBatchTableSql.selectAll(new MySqlDialect(), "shop", "orders"))
                .isEqualTo("select * from `shop`.`orders`");
    }

    @Test
    void fallsBackToAnsiQuotesForGenericSources() {
        assertThat(EtlBatchTableSql.selectAll(new GenericSqlDialect(), "shop", "orders"))
                .isEqualTo("select * from \"shop\".\"orders\"");
    }

    @Test
    void omitsABlankSchema() {
        assertThat(EtlBatchTableSql.selectAll(new GenericSqlDialect(), " ", "orders"))
                .isEqualTo("select * from \"orders\"");
    }

    @Test
    void doublesQuotesEmbeddedInTheName() {
        assertThat(EtlBatchTableSql.selectAll(new GenericSqlDialect(), null, "a\"b"))
                .isEqualTo("select * from \"a\"\"b\"");
    }
}
