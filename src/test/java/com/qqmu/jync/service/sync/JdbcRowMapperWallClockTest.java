package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * A {@code DATE}/{@code TIMESTAMP} column without a time zone is a wall clock, not an
 * instant. The JDBC {@code Types.TIMESTAMP} getter builds a {@link Timestamp} by reading
 * the wall clock in the JVM's default zone; Asia/Shanghai's historical DST rules make
 * several pre-1949 wall times nonexistent (1941-03-15 00:00, 1948-05-01 00:00, …), and
 * the zone rules move each of them forward by one hour. Oracle 11g → 19c syncs turned
 * every such value into the next hour for exactly this reason.
 *
 * <p>{@code getObject(idx, LocalDateTime.class)} hands back the stored wall clock
 * unchanged, and {@code setObject(idx, LocalDateTime)} writes it back unchanged. These
 * tests pin that both the row path and the cursor/watermark path use it, and that
 * zone-aware columns (MySQL {@code TIMESTAMP}, {@code TIMESTAMPTZ}) keep their instant
 * reads.
 */
class JdbcRowMapperWallClockTest {

    // —— 读取 ——

    @Test
    void anOracleTimestampColumnIsReadAsAWallClock() throws Exception {
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        ResultSet rs = mockRow(Types.TIMESTAMP, "TIMESTAMP");
        when(rs.getObject(1, LocalDateTime.class)).thenReturn(wall);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.TIMESTAMP), List.of("TIMESTAMP"), DatabaseType.ORACLE);

        assertThat(row.get("c")).isEqualTo(wall);
        verify(rs, never()).getTimestamp(1);
    }

    @Test
    void anOracleDateReportedAsTimestampKeepsItsWallClock() throws Exception {
        // Oracle 的 DATE 默认按 Types.TIMESTAMP 上报(驱动 mapDateToTimestamp),但它同样
        // 没有时区,必须走墙钟读取。
        LocalDateTime wall = LocalDateTime.of(1941, 3, 15, 0, 0);
        ResultSet rs = mockRow(Types.TIMESTAMP, "DATE");
        when(rs.getObject(1, LocalDateTime.class)).thenReturn(wall);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.TIMESTAMP), List.of("DATE"), DatabaseType.ORACLE);

        assertThat(row.get("c")).isEqualTo(wall);
    }

    @Test
    void everyShanghaiGapInstantSurvivesTheRead() throws Exception {
        // 用户报出的 6 个值正是 Asia/Shanghai 历史 DST 跳变的空缺时刻。
        List<LocalDateTime> gaps = List.of(
                LocalDateTime.of(1942, 1, 31, 1, 0),
                LocalDateTime.of(1948, 5, 1, 0, 0),
                LocalDateTime.of(1947, 4, 15, 0, 0),
                LocalDateTime.of(1941, 3, 15, 0, 0),
                LocalDateTime.of(1949, 5, 1, 1, 0));
        for (LocalDateTime wall : gaps) {
            ResultSet rs = mockRow(Types.TIMESTAMP, "DATE");
            when(rs.getObject(1, LocalDateTime.class)).thenReturn(wall);
            when(rs.wasNull()).thenReturn(false);

            Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                    List.of(Types.TIMESTAMP), List.of("DATE"), DatabaseType.ORACLE);

            assertThat(row.get("c")).as("%s must not move forward", wall).isEqualTo(wall);
        }
    }

    @Test
    void aMysqlTimestampColumnKeepsItsInstantRead() throws Exception {
        // MySQL 的 TIMESTAMP 自带时区语义,getTimestamp 的瞬时读取是正确行为。
        Timestamp instant = Timestamp.valueOf("2026-10-13 12:34:56");
        ResultSet rs = mockRow(Types.TIMESTAMP, "TIMESTAMP");
        when(rs.getTimestamp(1)).thenReturn(instant);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.TIMESTAMP), List.of("TIMESTAMP"), DatabaseType.MYSQL);

        assertThat(row.get("c")).isEqualTo(instant);
        verify(rs, never()).getObject(1, LocalDateTime.class);
    }

    @Test
    void aZoneAwareTimestampKeepsItsInstantRead() throws Exception {
        Timestamp instant = Timestamp.valueOf("2026-10-13 12:34:56");
        ResultSet rs = mockRow(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE");
        when(rs.getTimestamp(1)).thenReturn(instant);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.TIMESTAMP_WITH_TIMEZONE),
                List.of("TIMESTAMP WITH TIME ZONE"), DatabaseType.ORACLE);

        assertThat(row.get("c")).isEqualTo(instant);
    }

    @Test
    void aDriverWithoutLocalDateTimeSupportFallsBackToGetTimestamp() throws Exception {
        Timestamp instant = Timestamp.valueOf("2026-10-13 12:34:56");
        ResultSet rs = mockRow(Types.TIMESTAMP, "TIMESTAMP");
        when(rs.getObject(1, LocalDateTime.class))
                .thenThrow(new SQLFeatureNotSupportedException("not supported"));
        when(rs.getTimestamp(1)).thenReturn(instant);
        when(rs.wasNull()).thenReturn(false);

        Map<String, Object> row = JdbcRowMapper.readRow(rs, List.of("c"),
                List.of(Types.TIMESTAMP), List.of("TIMESTAMP"), DatabaseType.ORACLE);

        assertThat(row.get("c")).isEqualTo(instant);
    }

    // —— 绑定 ——

    @Test
    void aWallClockBindsThroughSetObjectSoTheGapIsNotFilledForward() throws Exception {
        // 没有目标方言的四参调用(老调用点)行为不变:驱动的 java.time 支持正确时
        // setObject 原样写入墙钟。
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", wall);

        JdbcRowMapper.bind(ps, row, List.of("c"), Map.of("c", Types.TIMESTAMP));

        verify(ps).setObject(1, wall);
        verify(ps, never()).setTimestamp(anyInt(), any());
    }

    @Test
    void anOracleTargetBindsAWallClockThroughAFixedOffsetCalendar() throws Exception {
        // 随包发布的 ojdbc8 21.5 把 setObject(LocalDateTime) 当成默认时区的瞬时值处理:
        // 1948-05-01 00:00(Asia/Shanghai 的历史 DST 空缺)会落成 01:00。带 UTC 日历的
        // setTimestamp 让驱动直接按该日历渲染字段,墙钟原样落库。
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", wall);

        JdbcRowMapper.bind(ps, row, List.of("c"), Map.of("c", Types.TIMESTAMP),
                new OracleDialect(19));

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> calendar != null
                        && "UTC".equals(calendar.getTimeZone().getID())));
        verify(ps, never()).setObject(eq(1), any());
    }

    @Test
    void aNonOracleTargetKeepsSetObjectForAWallClock() throws Exception {
        // 其它产品驱动的 java.time 支持没有这个问题;不要改动它们现有的写入路径。
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", wall);

        JdbcRowMapper.bind(ps, row, List.of("c"), Map.of("c", Types.TIMESTAMP),
                new MySqlDialect());

        verify(ps).setObject(1, wall);
        verify(ps, never()).setTimestamp(anyInt(), any(), any());
    }

    @Test
    void anOracleCursorParameterIsBoundThroughTheFixedOffsetCalendar() throws Exception {
        // 增量窗口的 WHERE cursor > ? 与 COUNT(*) 审计都走同一个绑定助手。
        LocalDateTime wall = LocalDateTime.of(1947, 4, 15, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);

        JdbcRowMapper.bindParameter(ps, 1, wall, new OracleDialect(19));

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> calendar != null
                        && "UTC".equals(calendar.getTimeZone().getID())));
        verify(ps, never()).setObject(eq(1), any());
    }

    @Test
    void aPostgresCursorParameterIsBoundThroughTheFixedOffsetCalendar() throws Exception {
        // PgJDBC 42.x routes both setObject(LocalDateTime) and
        // setObject(value, Types.TIMESTAMP) through Timestamp.valueOf, which reads the
        // fields in the JVM zone: 1947-04-15 00:00 is stored as 01:00 without the calendar.
        LocalDateTime wall = LocalDateTime.of(1947, 4, 15, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);

        JdbcRowMapper.bindParameter(ps, 1, wall, new PostgresDialect());

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> calendar != null
                        && "UTC".equals(calendar.getTimeZone().getID())));
        verify(ps, never()).setObject(eq(1), any());
    }

    @Test
    void aMySqlCursorParameterKeepsSetObject() throws Exception {
        // Connector/J sends the LocalDateTime as the wall clock itself; the calendar bind
        // is unnecessary there.
        LocalDateTime wall = LocalDateTime.of(1947, 4, 15, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);

        JdbcRowMapper.bindParameter(ps, 1, wall, new MySqlDialect());

        verify(ps).setObject(1, wall);
        verify(ps, never()).setTimestamp(anyInt(), any(), any());
    }

    @Test
    void aNonTemporalCursorParameterKeepsSetObject() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        JdbcRowMapper.bindParameter(ps, 1, 42L, new OracleDialect(19));

        verify(ps).setObject(1, 42L);
    }

    @Test
    void aDriverWithoutLocalDateTimeBindFallsBackToSetTimestamp() throws Exception {
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        PreparedStatement ps = mock(PreparedStatement.class);
        doThrow(new SQLFeatureNotSupportedException("not supported"))
                .when(ps).setObject(1, wall);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", wall);

        JdbcRowMapper.bind(ps, row, List.of("c"), Map.of("c", Types.TIMESTAMP));

        verify(ps).setTimestamp(1, Timestamp.valueOf(wall));
    }

    // —— 游标文本 ——

    @Test
    void aWallClockCursorKeepsItsWallClockText() {
        assertThat(JdbcRowMapper.cursorToString(LocalDateTime.of(1948, 5, 1, 0, 0)))
                .isEqualTo("1948-05-01 00:00:00");
    }

    @Test
    void wallClockTextParsesBackWithoutTheGapShift() {
        Object parsed = JdbcRowMapper.cursorFromString("1948-05-01 00:00:00", Types.TIMESTAMP);

        assertThat(parsed).isEqualTo(LocalDateTime.of(1948, 5, 1, 0, 0));
        assertThat(parsed).isNotInstanceOf(Timestamp.class);
    }

    @Test
    void aWallClockCursorRoundTripsWithFractionalSeconds() {
        LocalDateTime wall = LocalDateTime.of(1947, 4, 15, 0, 0, 0, 123_456_000);
        String text = JdbcRowMapper.cursorToString(wall);

        assertThat(JdbcRowMapper.cursorFromString(text, Types.TIMESTAMP)).isEqualTo(wall);
    }

    @Test
    void anInstantCursorStillParsesToATimestamp() {
        // 旧版本把无时区时间也存成 ISO instant;升级后的第一轮必须还能读。
        Object parsed = JdbcRowMapper.cursorFromString("2026-11-05T02:00:00Z", Types.TIMESTAMP);

        assertThat(parsed).isInstanceOf(Timestamp.class);
    }

    // —— 高水位 ——

    @Test
    void anOracleTimestampWatermarkIsReadAsAWallClock() throws Exception {
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1, LocalDateTime.class)).thenReturn(wall);
        when(rs.wasNull()).thenReturn(false);

        Object value = DataSyncService.readWatermarkValue(rs,
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP, "update time"),
                "DATE", DatabaseType.ORACLE);

        assertThat(value).isEqualTo(wall);
    }

    @Test
    void aZoneAwareWatermarkKeepsItsInstantRead() throws Exception {
        Timestamp instant = Timestamp.valueOf("2026-10-13 12:34:56");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1)).thenReturn(instant);
        when(rs.wasNull()).thenReturn(false);

        Object value = DataSyncService.readWatermarkValue(rs,
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP_WITH_TIMEZONE, "update time"),
                "TIMESTAMP WITH TIME ZONE", DatabaseType.ORACLE);

        assertThat(value).isEqualTo(instant);
    }

    @Test
    void theSafetyLagSubtractsFromAWallClockWatermark() {
        assertThat(DataSyncService.subtractSafetyLag("1948-05-01 00:00:00", 1000))
                .isEqualTo("1948-04-30 23:59:59");
    }

    @Test
    void theSafetyLagStillSubtractsFromAnInstantWatermark() {
        assertThat(DataSyncService.subtractSafetyLag("2026-11-05T10:00:00Z", 1000))
                .isEqualTo("2026-11-05T09:59:59Z");
    }

    @Test
    void theSafetyLagLeavesNonTemporalWatermarksAlone() {
        assertThat(DataSyncService.subtractSafetyLag("42", 1000)).isEqualTo("42");
    }

    @Test
    void aWallClockWatermarkMovedPastALegacyInstantWatermark() {
        // 升级后的第一轮:旧游标是 instant,新水位是墙钟,两者必须能比大小。
        Timestamp legacy = Timestamp.valueOf(LocalDateTime.of(1948, 5, 1, 0, 0));

        assertThat(DataSyncService.isAfter(LocalDateTime.of(2026, 11, 5, 10, 0), legacy))
                .isTrue();
        assertThat(DataSyncService.isAfter(LocalDateTime.of(1948, 5, 1, 0, 0), legacy))
                .isFalse();
    }

    // —— 全量切分边界 ——

    @Test
    void aWallClockChunkBoundaryRoundTripsWithoutTheGapShift() {
        List<Object> boundary = List.of(LocalDateTime.of(1948, 5, 1, 0, 0),
                LocalDateTime.of(1947, 4, 15, 0, 0, 0, 123_456_000));

        List<Object> decoded = KeysetChunker.decodePk(KeysetChunker.encodePk(boundary),
                List.of(Types.TIMESTAMP, Types.TIMESTAMP));

        assertThat(decoded).containsExactly(LocalDateTime.of(1948, 5, 1, 0, 0),
                LocalDateTime.of(1947, 4, 15, 0, 0, 0, 123_456_000));
    }

    @Test
    void aLegacyEpochMillisBoundaryStillDecodesToATimestamp() {
        List<Object> decoded = KeysetChunker.decodePk("[1700000000123]",
                List.of(Types.TIMESTAMP));

        assertThat(decoded.get(0)).isInstanceOf(Timestamp.class);
    }

    private static ResultSet mockRow(int jdbcType, String typeName) throws Exception {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("c");
        when(md.getColumnType(1)).thenReturn(jdbcType);
        when(md.getColumnTypeName(1)).thenReturn(typeName);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(true, false);
        return rs;
    }
}
