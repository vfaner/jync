package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;

/**
 * The ETL extraction path must read a TDengine cell exactly like the sync path: text columns
 * through getString (the driver's getObject returns byte[]) and unsigned big integers through
 * getBigDecimal (a BigInteger cannot be bound on pgjdbc).
 */
class JdbcEtlSourceCursorTdengineTest {

    private ResultSet resultSet;

    @Test
    void tdengineVarcharIsReturnedAsText() throws Exception {
        JdbcEtlSourceCursor cursor = cursor(Types.VARCHAR, "BINARY");
        when(resultSet.getObject(1)).thenReturn("hello".getBytes());
        when(resultSet.getString(1)).thenReturn("hello");

        assertThat(cursor.getObject(1)).isEqualTo("hello");
    }

    @Test
    void tdengineDecimalIsReturnedAsBigDecimal() throws Exception {
        JdbcEtlSourceCursor cursor = cursor(Types.DECIMAL, "BIGINT UNSIGNED");
        when(resultSet.getObject(1)).thenReturn(BigInteger.TEN);
        when(resultSet.getBigDecimal(1)).thenReturn(BigDecimal.TEN);

        assertThat(cursor.getObject(1)).isEqualTo(BigDecimal.TEN);
    }

    private JdbcEtlSourceCursor cursor(int type, String typeName) throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("c");
        when(meta.getColumnType(1)).thenReturn(type);
        when(meta.getColumnTypeName(1)).thenReturn(typeName);
        when(meta.getPrecision(1)).thenReturn(0);
        when(meta.getScale(1)).thenReturn(0);
        when(meta.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
        resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(meta);
        return new JdbcEtlSourceCursor(mock(PreparedStatement.class), resultSet,
                new EtlTask(), false, DatabaseType.TDENGINE);
    }
}
