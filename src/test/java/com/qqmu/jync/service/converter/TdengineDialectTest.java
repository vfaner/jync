package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

class TdengineDialectTest {

    private final TdengineDialect dialect = new TdengineDialect();

    @Test
    void quotesWithBackticksAndKeepsLimitOffset() {
        assertThat(dialect.explicitType()).isEqualTo(DatabaseType.TDENGINE);
        assertThat(dialect.qualify("iot", "events")).isEqualTo("`iot`.`events`");
        assertThat(dialect.getPaginationSql("SELECT * FROM t", 0, 10))
                .isEqualTo("SELECT * FROM t LIMIT 10");
        assertThat(dialect.getPaginationSql("SELECT * FROM t", 20, 10))
                .isEqualTo("SELECT * FROM t LIMIT 10 OFFSET 20");
    }

    @Test
    void hasNoWindowFunctions() {
        assertThat(dialect.supportsWindowFunctions()).isFalse();
    }

    @Test
    void readsTheUpperBoundWithLastBecauseMaxRejectsTimestamps() {
        // TDengine 3.3.6 answers MAX(`ts`) with "Invalid parameter data type : max";
        // LAST returns the value of the row with the greatest timestamp.
        assertThat(dialect.highWatermarkMaxExpression("`ts`")).isEqualTo("LAST(`ts`)");
    }
}
