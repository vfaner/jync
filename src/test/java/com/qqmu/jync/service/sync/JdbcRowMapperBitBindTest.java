package com.qqmu.jync.service.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * MySQL BIT(n&gt;1) values arrive as byte arrays (big-endian bit strings), but after the
 * width-aware mapping they land in SMALLINT/INT/BIGINT/DECIMAL target columns. The bind
 * must therefore turn the byte array into the unsigned integer it represents rather than
 * sending bytes (which PostgreSQL-family targets reject for integer columns). BIT(1)
 * values that a binlog decoder may also deliver as a 1-byte array must bind as boolean.
 */
class JdbcRowMapperBitBindTest {

    private final PreparedStatement ps = mock(PreparedStatement.class);

    private static void bind(PreparedStatement ps, String column, Object value,
                             int targetJdbcType) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(column, value);
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put(column, targetJdbcType);
        JdbcRowMapper.bind(ps, row, List.of(column), types);
    }

    @Test
    void bitBytesBindAsBigIntegerToSmallintTarget() throws Exception {
        bind(ps, "c", new byte[] {(byte) 0xAA}, Types.SMALLINT);
        verify(ps).setObject(1, BigInteger.valueOf(0xAA));
    }

    @Test
    void bitBytesBindAsBigIntegerToBigintTarget() throws Exception {
        bind(ps, "c", new byte[] {0x12, 0x34}, Types.BIGINT);
        verify(ps).setObject(1, BigInteger.valueOf(0x1234));
    }

    @Test
    void singleBitBytesBindAsBooleanToBooleanTarget() throws Exception {
        bind(ps, "c", new byte[] {1}, Types.BIT);
        verify(ps).setBoolean(1, true);
    }

    @Test
    void genuineVarbinaryStillBindsAsBytes() throws Exception {
        byte[] data = {(byte) 0xDE, (byte) 0xAD};
        bind(ps, "c", data, Types.VARBINARY);
        verify(ps).setBytes(1, data);
    }

    @Test
    void booleanValueToNumericTargetBindsAsInteger() throws Exception {
        // Some binlog decoders surface BIT(1) as Boolean even when the target column was
        // hand-built as TINYINT; Boolean→SMALLINT does not coerce implicitly.
        bind(ps, "c", Boolean.TRUE, Types.SMALLINT);
        verify(ps).setObject(1, 1);
    }
}
