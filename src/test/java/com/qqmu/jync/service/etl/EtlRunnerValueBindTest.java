package com.qqmu.jync.service.etl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.lob.LobBinder;

/**
 * The copy loop must bind values the way the sync writer does, not with a bare
 * {@code setObject}: the source metadata maps a MySQL {@code BIT(8)} onto an integer target
 * column, but the driver hands the value over as a big-endian byte array, and PostgreSQL-
 * family targets refuse the Boolean some drivers return for {@code BIT(1)}. Bound verbatim,
 * those values either land as garbage or fail the whole task.
 *
 * <p>The narrowing only applies to a target column that actually is numeric; genuine binary
 * columns keep their bytes.
 */
class EtlRunnerValueBindTest {

    private final PreparedStatement insert = mock(PreparedStatement.class);

    @Test
    void aBitByteArrayBindsAsTheUnsignedIntegerItsColumnHolds() throws SQLException {
        EtlRunner.bindRow(insert, List.of(Types.SMALLINT),
                new Object[] {new byte[] {0x01, 0x02}}, new OracleDialect(), LobBinder.NOOP);

        verify(insert).setObject(1, BigInteger.valueOf(258));
        verify(insert, never()).setBytes(anyInt(), any());
    }

    @Test
    void aBooleanBindsAsOneOrZeroIntoAnIntegerTarget() throws SQLException {
        EtlRunner.bindRow(insert, List.of(Types.SMALLINT, Types.INTEGER),
                new Object[] {true, false}, new MySqlDialect(), LobBinder.NOOP);

        verify(insert).setObject(eq(1), eq(1));
        verify(insert).setObject(eq(2), eq(0));
    }

    @Test
    void binaryIntoABinaryTargetKeepsItsRawBytes() throws SQLException {
        byte[] value = {0x00, 0x7F, (byte) 0x80};

        EtlRunner.bindRow(insert, List.of(Types.BLOB), new Object[] {value},
                new OracleDialect(), LobBinder.NOOP);

        verify(insert).setBytes(1, value);
        verify(insert, never()).setObject(anyInt(), any());
    }

    /**
     * {@code java.time} values from the source must be normalized before they reach a target
     * driver, which is what the sync writer does. A driver without {@code setObject(LocalDate)}
     * support would otherwise reject an extract row it accepted from the sync path.
     */
    @Test
    void aLocalDateBindsAsADateNotAsAnOpaqueObject() throws SQLException {
        LocalDate value = LocalDate.of(2026, 11, 18);

        EtlRunner.bindRow(insert, List.of(Types.DATE), new Object[] {value},
                new MySqlDialect(), LobBinder.NOOP);

        verify(insert).setDate(1, java.sql.Date.valueOf(value));
        verify(insert, never()).setObject(anyInt(), any());
    }
}
