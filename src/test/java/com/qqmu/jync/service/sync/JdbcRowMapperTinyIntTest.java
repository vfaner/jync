package com.qqmu.jync.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.PostgresDialect;

/**
 * Connector/J reports MySQL {@code TINYINT(1)} as JDBC {@code BIT} and hands the cell over
 * as {@code Boolean}, so 2..127 collapse to true. MySQL's own dialect maps that same JDBC
 * type to {@code TINYINT(1)}, so the target can hold the number; both the read and the bind
 * must stop narrowing it.
 *
 * <p>Docker 实测 (mysql:8.4.11 + Connector/J 8.0.33): {@code TINYINT(1)} and genuine
 * {@code BIT(1)} are indistinguishable in the metadata — both report jdbc=BIT, typeName=BIT,
 * precision=1. {@code getInt} on a real {@code BIT(1)} returns the identical 0/1, so reading
 * every boolean-collapsed cell numerically is lossless for both.
 */
class JdbcRowMapperTinyIntTest {

    @Test
    void mysqlTinyIntOneReadsTheNumberNotTheBoolean() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(Boolean.TRUE);
        when(rs.getInt(1)).thenReturn(5);

        Object value = JdbcRowMapper.readValue(rs, 1, Types.BIT, "BIT", DatabaseType.MYSQL);

        assertEquals(5, value);
        assertInstanceOf(Integer.class, value);
    }

    @Test
    void mysqlGenuineBitOneStillReadsZeroOrOne() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(Boolean.FALSE);
        when(rs.getInt(1)).thenReturn(0);

        assertEquals(0, JdbcRowMapper.readValue(rs, 1, Types.BIT, "BIT", DatabaseType.MYSQL));
    }

    @Test
    void mysqlWideBitKeepsItsByteArray() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        byte[] bits = {0x05};
        when(rs.getObject(1)).thenReturn(bits);

        assertEquals(bits, JdbcRowMapper.readValue(rs, 1, Types.BIT, "BIT", DatabaseType.MYSQL));
        verify(rs, never()).getInt(anyInt());
    }

    @Test
    void nonMysqlBooleanSourceStaysBoolean() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(Boolean.TRUE);

        assertEquals(Boolean.TRUE,
                JdbcRowMapper.readValue(rs, 1, Types.BIT, "BIT", DatabaseType.SQLSERVER));
        verify(rs, never()).getInt(anyInt());
    }

    /**
     * Doris speaks the MySQL wire protocol through Connector/J but carries GENERIC as its
     * dialect family (its DDL/upsert differ from MySQL's). Its TINYINT(1) is the same
     * 1-byte integer, so the write-side family rule must not lose it on the read.
     */
    @Test
    void dorisTinyIntOneReadsTheNumberNotTheBoolean() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(Boolean.TRUE);
        when(rs.getInt(1)).thenReturn(5);

        Object value = JdbcRowMapper.readValue(rs, 1, Types.BIT, "BIT", DatabaseType.DORIS);

        assertEquals(5, value);
    }

    @Test
    void numberBindsVerbatimToMysqlTinyIntOneTarget() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        bind(ps, 5, Types.BIT, new MySqlDialect());

        verify(ps).setObject(1, 5);
        verify(ps, never()).setBoolean(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void numberStillBindsAsBooleanToPostgresBooleanTarget() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        bind(ps, 5, Types.BIT, new PostgresDialect());

        verify(ps).setBoolean(1, true);
        verify(ps, never()).setObject(1, 5);
    }

    @Test
    void numberStillBindsAsBooleanWithoutADialect() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        bind(ps, 0, Types.BOOLEAN, null);

        verify(ps).setBoolean(1, false);
    }

    private static void bind(PreparedStatement ps, int value, int targetJdbcType,
                             com.qqmu.jync.service.converter.SqlDialect dialect) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", value);
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("c", targetJdbcType);
        JdbcRowMapper.bind(ps, row, List.of("c"), types, dialect);
    }
}
