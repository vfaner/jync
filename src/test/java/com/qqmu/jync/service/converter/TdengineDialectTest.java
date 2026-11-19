package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

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
    void readsATimestampUpperBoundWithLastBecauseMaxRejectsTimestamps() {
        // TDengine 3.3.6 answers MAX(`ts`) with "Invalid parameter data type : max";
        // LAST returns the value of the row with the greatest timestamp.
        assertThat(dialect.highWatermarkMaxExpression("`ts`", Types.TIMESTAMP))
                .isEqualTo("LAST(`ts`)");
    }

    @Test
    void readsANumericUpperBoundWithMaxBecauseLastIsNotAMaximum() {
        // LAST(id) is the id of the newest row by ts, not the largest id: with rows
        // (t1,1), (t2,3), (t3,2) it answers 2 while MAX answers 3. MAX works on INT,
        // BIGINT and VARCHAR here, so only timestamps need LAST.
        assertThat(dialect.highWatermarkMaxExpression("`id`", Types.BIGINT))
                .isEqualTo("MAX(`id`)");
        assertThat(dialect.highWatermarkMaxExpression("`name`", Types.VARCHAR))
                .isEqualTo("MAX(`name`)");
    }
}
