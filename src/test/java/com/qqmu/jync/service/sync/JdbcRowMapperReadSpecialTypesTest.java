package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Source columns whose JDBC type misreports the actual value must be read by their real
 * type name:
 * <ul>
 *   <li>MySQL YEAR reports JDBC DATE, but the value is an integer year; reading it as a
 *       date sent "2026-01-01 +08" into a SMALLINT column and parked the baseline.</li>
 *   <li>MySQL DATETIME reports JDBC TIMESTAMP; Connector/J converts it from the server
 *       session zone to the JVM zone in getTimestamp, shifting the wall clock (12:34
 *       arrived as 20:34). Reading the server literal preserves the displayed wall time.</li>
 * </ul>
 */
class JdbcRowMapperReadSpecialTypesTest {

    @Test
    void mysqlYearIsReadAsAnIntegerYear() throws Exception {
        ResultSet rs = mockRow(Types.DATE, "YEAR");
        when(rs.getInt(1)).thenReturn(2026);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.DATE), List.of("YEAR"));

        assertThat(row.get("c")).isEqualTo(2026);
    }

    @Test
    void mysqlDatetimeIsReadAsTheServerWallClockLiteral() throws Exception {
        ResultSet rs = mockRow(Types.TIMESTAMP, "DATETIME");
        when(rs.getString(1)).thenReturn("2026-10-13 12:34:56.789012");
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.TIMESTAMP), List.of("DATETIME"));

        assertThat(row.get("c")).isEqualTo("2026-10-13 12:34:56.789012");
    }

    @Test
    void aGenuineTimestampColumnIsStillReadAsTimestamp() throws Exception {
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf("2026-10-13 12:34:56.789012");
        ResultSet rs = mockRow(Types.TIMESTAMP, "TIMESTAMP");
        when(rs.getTimestamp(1)).thenReturn(ts);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.TIMESTAMP), List.of("TIMESTAMP"));

        assertThat(row.get("c")).isEqualTo(ts);
    }

    @Test
    void nullYearStaysNull() throws Exception {
        ResultSet rs = mockRow(Types.DATE, "YEAR");
        when(rs.getInt(1)).thenReturn(0);
        when(rs.wasNull()).thenReturn(true);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.DATE), List.of("YEAR"));

        assertThat(row.get("c")).isNull();
    }

    // —— F-9:PG JSON/JSONB/UUID 报 Types.OTHER,getObject 返回 PGobject(驱动私有类),
    // 目标 TEXT/VARCHAR 收到 setObject(PGobject) 无法推断类型。它们的文本形态就是
    // 通用载荷,必须 getString 读取。 ——

    @Test
    void pgJsonbIsReadAsItsTextPayload() throws Exception {
        ResultSet rs = mockRow(Types.OTHER, "jsonb");
        when(rs.getString(1)).thenReturn("{\"k\": 1}");
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.OTHER), List.of("jsonb"));

        assertThat(row.get("c")).isEqualTo("{\"k\": 1}");
    }

    @Test
    void pgUuidIsReadAsItsTextForm() throws Exception {
        ResultSet rs = mockRow(Types.OTHER, "uuid");
        when(rs.getString(1)).thenReturn("550e8400-e29b-41d4-a716-446655440000");
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.OTHER), List.of("uuid"));

        assertThat(row.get("c")).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void aNonTextOtherTypeIsStillReadAsObject() throws Exception {
        // 守护:几何类型 point 的 getObject 是 PGpoint,不是字符串载荷,不能扩大化
        // 把所有 OTHER 都读成文本。
        Object point = new Object();
        ResultSet rs = mockRow(Types.OTHER, "point");
        when(rs.getObject(1)).thenReturn(point);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(
                rs, List.of("c"), List.of(Types.OTHER), List.of("point"));

        assertThat(row.get("c")).isSameAs(point);
    }

    // —— PG 枚举与数组:DDL 侧落成 TEXT/CLOB,行值也必须是文本。枚举报 Types.OTHER
    // (TYPE_NAME=枚举名),数组报 Types.ARRAY(TYPE_NAME="_int4");旧代码分别走
    // getObject → PGobject / PgArray,非 PG 目标驱动直接拒绝绑定。 ——

    @Test
    void pgEnumValueIsReadAsText() throws Exception {
        ResultSet rs = mockRow(Types.OTHER, "mood");
        when(rs.getString(1)).thenReturn("happy");
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.OTHER), List.of("mood"), com.qqmu.jync.model.DatabaseType.POSTGRESQL);

        assertThat(row.get("c")).isEqualTo("happy");
    }

    @Test
    void pgArrayValueIsReadAsText() throws Exception {
        ResultSet rs = mockRow(Types.ARRAY, "_int4");
        when(rs.getString(1)).thenReturn("{1,2,3}");
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.ARRAY), List.of("_int4"),
                com.qqmu.jync.model.DatabaseType.POSTGRESQL);

        assertThat(row.get("c")).isEqualTo("{1,2,3}");
    }

    @Test
    void nonPgArrayKeepsGetObjectBehaviour() throws Exception {
        // Source family is not PG: keep the driver's natural reading rather than guessing.
        Object nativeArray = new Object();
        ResultSet rs = mockRow(Types.ARRAY, "ARRAY");
        when(rs.getObject(1)).thenReturn(nativeArray);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.ARRAY), List.of("ARRAY"),
                com.qqmu.jync.model.DatabaseType.ORACLE);

        assertThat(row.get("c")).isSameAs(nativeArray);
    }

    private static ResultSet mockRow(int jdbcType, String typeName) throws Exception {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("c");
        when(md.getColumnType(1)).thenReturn(jdbcType);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(true, false);
        return rs;
    }
}
