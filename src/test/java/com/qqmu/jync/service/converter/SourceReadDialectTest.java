package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The six analytic / search engines are sync sources only, so their dialects exist solely for
 * the read path: identifier quoting and the pagination tail. They inherit every write-side
 * statement from {@link GenericSqlDialect}, which is exactly why they must never be a target.
 *
 * <p>OFFSET is only offered where the product's own grammar makes it safe. Hive and Impala both
 * document an offset form, but only from server releases this build cannot verify; Elasticsearch
 * SQL and Atlas SQL have none at all. The existing {@code supportsOffsetPaging()} contract —
 * "if you cannot verify it, answer false" — covers all four.
 */
class SourceReadDialectTest {

    private final SqlDialectFactory factory = new SqlDialectFactory(List.of(
            new GenericSqlDialect(),
            new MySqlDialect(),
            new PollingSourceDialect(DatabaseType.DORIS, '`', true),
            new PollingSourceDialect(DatabaseType.CLICKHOUSE, '`', true),
            new PollingSourceDialect(DatabaseType.HIVE, '`', false),
            new PollingSourceDialect(DatabaseType.IMPALA, '`', false),
            new PollingSourceDialect(DatabaseType.ELASTICSEARCH, '"', false),
            new PollingSourceDialect(DatabaseType.MONGODB, '"', false)),
            new GenericSqlDialect());

    @Test
    void offsetCapableEnginesPageWithLimitOffset() {
        PollingSourceDialect doris = new PollingSourceDialect(DatabaseType.DORIS, '`', true);

        assertThat(doris.getPaginationSql("SELECT `ID` FROM `T`", 20, 10))
                .isEqualTo("SELECT `ID` FROM `T` LIMIT 10 OFFSET 20");
        // The keyset walk always passes offset 0; a trailing OFFSET 0 adds nothing.
        assertThat(doris.getPaginationSql("SELECT `ID` FROM `T`", 0, 10))
                .isEqualTo("SELECT `ID` FROM `T` LIMIT 10");
        assertThat(doris.supportsOffsetPaging()).isTrue();
    }

    @Test
    void aKeysetPageNeedsNoOffsetAndWorksOnEveryEngine() {
        // KeysetChunker.pageSql always passes offset 0 — the predicate did the seeking.
        PollingSourceDialect clickHouse =
                new PollingSourceDialect(DatabaseType.CLICKHOUSE, '`', true);
        assertThat(clickHouse.getPaginationSql("SELECT * FROM `T` ORDER BY `ID`", 0, 500))
                .isEqualTo("SELECT * FROM `T` ORDER BY `ID` LIMIT 500");
    }

    @Test
    void limitOnlyEnginesRefuseANonZeroOffset() {
        for (DatabaseType type : List.of(DatabaseType.HIVE, DatabaseType.IMPALA,
                DatabaseType.ELASTICSEARCH, DatabaseType.MONGODB)) {
            PollingSourceDialect dialect = new PollingSourceDialect(type, '`', false);

            assertThat(dialect.supportsOffsetPaging()).as(type.name()).isFalse();
            assertThat(dialect.getPaginationSql("SELECT 1", 0, 10)).as(type.name())
                    .isEqualTo("SELECT 1 LIMIT 10");
            assertThatThrownBy(() -> dialect.getPaginationSql("SELECT 1", 10, 10))
                    .as(type.name())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void quotingFollowsTheEngine() {
        assertThat(new PollingSourceDialect(DatabaseType.CLICKHOUSE, '`', true)
                .quoteIdentifier("order-id")).isEqualTo("`order-id`");
        assertThat(new PollingSourceDialect(DatabaseType.ELASTICSEARCH, '"', false)
                .quoteIdentifier("order-id")).isEqualTo("\"order-id\"");
        // An embedded quote is doubled, never stripped: stripping would rename the column.
        assertThat(new PollingSourceDialect(DatabaseType.MONGODB, '"', false)
                .quoteIdentifier("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test
    void theFactoryRoutesEveryPollingOnlyPresetToItsReadDialect() {
        for (DatabaseType type : List.of(DatabaseType.DORIS, DatabaseType.CLICKHOUSE,
                DatabaseType.HIVE, DatabaseType.IMPALA,
                DatabaseType.ELASTICSEARCH, DatabaseType.MONGODB)) {
            assertThat(factory.forType(type).explicitType()).as(type.name()).isEqualTo(type);
        }
    }

    @Test
    void theSameFactoryStillServesFamiliesAndTheGenericFallback() {
        assertThat(factory.forType(DatabaseType.MYSQL)).isInstanceOf(MySqlDialect.class);
        for (DatabaseType type : List.of(DatabaseType.H2, DatabaseType.MONGODB_NATIVE)) {
            assertThat(factory.forType(type)).as(type.name())
                    .isExactlyInstanceOf(GenericSqlDialect.class);
        }
        assertThat(factory.forType(DatabaseType.CUSTOM, null,
                DatabaseType.DialectFamily.GENERIC))
                .isExactlyInstanceOf(GenericSqlDialect.class);
    }
}
