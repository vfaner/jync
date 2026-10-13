package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL BIT is width-sensitive: BIT(1) is a boolean, but BIT(n&gt;1) is an n-bit unsigned
 * integer that Connector/J hands back as a byte array. Mapping every BIT to BOOLEAN (the
 * old behaviour) built BIT(8) columns as boolean and parked the baseline in the docker
 * matrix. Width bands follow the value range: 2-8 SMALLINT, 9-16 INT, 17-63 BIGINT,
 * BIT(64) needs an exact (20,0)-capable decimal since 2^64-1 overflows signed BIGINT.
 */
class MySqlBitTypeMappingTest {

    private final PostgresDialect postgres = new PostgresDialect();
    private final OpenGaussDialect openGauss = new OpenGaussDialect();

    private static ColumnMeta bit(int width) {
        ColumnMeta c = new ColumnMeta();
        c.setName("c_bit" + width);
        c.setTypeName("BIT");
        c.setJdbcType(Types.BIT);
        c.setSize(width);
        c.setNullable(true);
        c.setOrdinalPosition(1);
        return c;
    }

    @Test
    void singleBitStaysBoolean() {
        assertThat(postgres.mapType(bit(1), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("BOOLEAN");
    }

    @Test
    void bitUpTo8MapsToSmallint() {
        assertThat(postgres.mapType(bit(8), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("SMALLINT");
        assertThat(openGauss.mapType(bit(8), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("SMALLINT");
    }

    @Test
    void bitUpTo16MapsToInteger() {
        assertThat(postgres.mapType(bit(16), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("INTEGER");
    }

    @Test
    void bitUpTo63MapsToBigint() {
        assertThat(postgres.mapType(bit(32), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("BIGINT");
    }

    @Test
    void bit64MapsToAnExactDecimal() {
        // 2^64-1 does not fit signed BIGINT; the target must be an exact decimal type.
        String mapped = postgres.mapType(bit(64), DatabaseType.MYSQL).toUpperCase();
        assertThat(mapped).startsWith("DECIMAL(");
    }

    @Test
    void sqlServerBooleanBitIsUnaffected() {
        // SQL Server's BIT has no width and is a genuine boolean; size 1 keeps it BOOLEAN.
        assertThat(postgres.mapType(bit(1), DatabaseType.SQLSERVER).toUpperCase())
                .isEqualTo("BOOLEAN");
    }
}
