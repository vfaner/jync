package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The cursor is persisted as text and re-read after a restart, so a round-trip that loses
 * precision or timezone would silently skip or replay rows.
 */
class JdbcRowMapperCursorTest {

    @Test
    void timestampCursorRoundTripsThroughItsStoredForm() {
        Instant original = Instant.parse("2026-06-01T12:34:56.789Z");
        Timestamp source = Timestamp.from(original);

        String stored = JdbcRowMapper.cursorToString(source);
        Object restored = JdbcRowMapper.cursorFromString(stored, Types.TIMESTAMP);

        assertThat(restored).isInstanceOf(Timestamp.class);
        assertThat(((Timestamp) restored).toInstant()).isEqualTo(original);
    }

    @Test
    void timestampIsStoredInUtcSoAServerTimezoneChangeCannotShiftIt() {
        Timestamp source = Timestamp.from(Instant.parse("2026-06-01T00:00:00Z"));
        String stored = JdbcRowMapper.cursorToString(source);
        // An ISO-8601 instant is unambiguous; a local "yyyy-MM-dd HH:mm:ss" would not be.
        assertThat(stored).endsWith("Z");
    }

    @Test
    void millisecondPrecisionSurvivesTheRoundTrip() {
        // Losing sub-second precision would re-deliver a whole second of rows every cycle.
        Timestamp source = Timestamp.from(Instant.parse("2026-06-01T12:00:00.001Z"));
        String stored = JdbcRowMapper.cursorToString(source);
        Timestamp restored = (Timestamp) JdbcRowMapper.cursorFromString(stored, Types.TIMESTAMP);
        assertThat(restored.toInstant()).isEqualTo(source.toInstant());
    }

    @Test
    void timeCursorRoundTrips() {
        // TIME is in CursorTypes.isTemporal, so a TIME column can be chosen as the cursor.
        // The mapper used to store it as "14:05:06" but parse temporal cursors only via
        // Instant.parse / Timestamp.valueOf — both fail on a bare time, the null bound then
        // reached List.of(...) and the whole full load died with an NPE.
        java.sql.Time source = java.sql.Time.valueOf("14:05:06");

        String stored = JdbcRowMapper.cursorToString(source);
        Object restored = JdbcRowMapper.cursorFromString(stored, Types.TIME);

        assertThat(stored).isEqualTo("14:05:06");
        assertThat(restored).isInstanceOf(java.sql.Time.class);
        assertThat(restored.toString()).isEqualTo("14:05:06");
    }

    @Test
    void aLocalTimeWatermarkIsStoredWithSecondsSoItParsesBack() {
        // Drivers may hand back java.time.LocalTime. LocalTime.toString() omits ":00"
        // seconds ("09:30"), which java.sql.Time.valueOf rejects — the stored form must
        // be normalized, not String.valueOf'd.
        String stored = JdbcRowMapper.cursorToString(java.time.LocalTime.of(9, 30));
        assertThat(stored).isEqualTo("09:30:00");
        assertThat(JdbcRowMapper.cursorFromString(stored, Types.TIME))
                .isInstanceOf(java.sql.Time.class);
    }

    @Test
    void anUnparseableTimeCursorIsTreatedAsAbsent() {
        assertThat(JdbcRowMapper.cursorFromString("not-a-time", Types.TIME)).isNull();
        assertThat(JdbcRowMapper.cursorFromString("2026-10-10 12:00:00", Types.TIME)).isNull();
    }

    // —— 裁定 R5:TIME 的合法全域(>24h、负值、小数秒)必须原样绑定回去;
    // Time.valueOf 解析不了这些字面量,旧代码 warn+null 会把窗口整个回卷。——

    @Test
    void extremeAndFractionalTimeCursorsBindBackAsLiterals() {
        assertThat(JdbcRowMapper.cursorFromString("838:59:59", Types.TIME))
                .isEqualTo("838:59:59");
        assertThat(JdbcRowMapper.cursorFromString("-10:30:00", Types.TIME))
                .isEqualTo("-10:30:00");
        assertThat(JdbcRowMapper.cursorFromString("12:34:56.789012", Types.TIME))
                .isEqualTo("12:34:56.789012");
        assertThat(JdbcRowMapper.cursorFromString("-838:59:59.999999", Types.TIME))
                .isEqualTo("-838:59:59.999999");
        // 常规值仍走 Time.valueOf(与旧行为逐字节一致)
        assertThat(JdbcRowMapper.cursorFromString("14:05:06", Types.TIME))
                .isInstanceOf(java.sql.Time.class);
    }

    @Test
    void nineDigitFractionTimeCursorBindsBackAsLiteral() {
        // SQL Server TIME(7) 可带 7 位小数,驱动/服务器字面量最多可到 9 位(纳秒)。
        // 旧的 {1,6} 正则把 >6 位小数判为垃圾 → warn+null → 游标视为不存在 →
        // 每一轮都全量重播种(数据安全但纯浪费)。9 位小数必须原样绑定回去。
        String stored = JdbcRowMapper.cursorToString("12:34:56.123456789");
        assertThat(stored).isEqualTo("12:34:56.123456789");
        assertThat(JdbcRowMapper.cursorFromString(stored, Types.TIME))
                .isEqualTo("12:34:56.123456789");
        assertThat(JdbcRowMapper.cursorFromString("-838:59:59.123456789", Types.TIME))
                .isEqualTo("-838:59:59.123456789");
        // 10 位小数不是任何合法 TIME 字面量:仍按垃圾处理(warn+null),边界不外扩。
        assertThat(JdbcRowMapper.cursorFromString("12:34:56.1234567890", Types.TIME)).isNull();
    }

    @Test
    void readRowKeepsTimeAsTheServerLiteral() throws Exception {
        // 读侧:rs.getTime 对 838:59:59 / -10:30:00 直接抛 DataReadException(Connector/J
        // SqlTimeValueFactory 拒收 hours<0 或 >=24),一行极端值就能停掉整个基线;
        // getString 拿服务器字面量,bindValue 走 setString 原样写回。
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(rs.getString(1)).thenReturn("838:59:59");
        org.mockito.Mockito.when(rs.wasNull()).thenReturn(false);
        java.util.Map<String, Object> row = JdbcRowMapper.readRow(
                rs, java.util.List.of("v"), java.util.List.of(Types.TIME));
        assertThat(row.get("v")).isEqualTo("838:59:59");
        org.mockito.Mockito.verify(rs, org.mockito.Mockito.never())
                .getTime(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void dateCursorRoundTripsAsWallDateRegardlessOfTimezone() {
        // 旧实现把 DATE 编成"UTC 零点时刻的 instant"，再经目标端时区解码，可能差一天。
        java.sql.Date source = java.sql.Date.valueOf("2026-01-01");

        String stored = JdbcRowMapper.cursorToString(source);
        Object restored = JdbcRowMapper.cursorFromString(stored, Types.DATE);

        assertThat(stored).isEqualTo("2026-01-01");
        assertThat(restored).isInstanceOf(java.sql.Date.class);
        assertThat(restored.toString()).isEqualTo("2026-01-01");
    }

    @Test
    void aLegacyDateInstantStillParses() {
        // Pre-fix cursors were midnight-UTC instants; they must decode in UTC, not shift.
        Object restored = JdbcRowMapper.cursorFromString("2026-01-01T00:00:00Z", Types.DATE);
        assertThat(restored).isInstanceOf(java.sql.Date.class);
        assertThat(restored.toString()).isEqualTo("2026-01-01");
    }

    @Test
    void localDateTimeCursorKeepsWallTimeInsteadOfInventingUtc() {
        // A zone-less 08:00 wall time must not be serialized as 08:00Z and rebound through
        // another zone — that is the DATETIME window shift that silently dropped rows.
        java.time.LocalDateTime source = java.time.LocalDateTime.of(2026, 1, 1, 8, 0, 0);

        String stored = JdbcRowMapper.cursorToString(source);
        Timestamp restored = (Timestamp) JdbcRowMapper.cursorFromString(stored, Types.TIMESTAMP);

        assertThat(stored).doesNotEndWith("Z");
        assertThat(restored.toLocalDateTime()).isEqualTo(source);
    }

    @Test
    void numericCursorRoundTrips() {
        String stored = JdbcRowMapper.cursorToString(123456789L);
        Object restored = JdbcRowMapper.cursorFromString(stored, Types.BIGINT);
        assertThat(restored).isEqualTo(123456789L);
    }

    @Test
    void aCursorTooLargeForALongStillParses() {
        // Oracle NUMBER can exceed long range; falling back to BigDecimal avoids a crash.
        String huge = "123456789012345678901234567890";
        Object restored = JdbcRowMapper.cursorFromString(huge, Types.NUMERIC);
        assertThat(restored).isInstanceOf(java.math.BigDecimal.class);
    }

    @Test
    void anUnparseableStoredCursorIsTreatedAsAbsentRatherThanThrowing() {
        // A corrupt value must degrade to a full reload, not break the project permanently.
        assertThat(JdbcRowMapper.cursorFromString("not-a-timestamp", Types.TIMESTAMP)).isNull();
        assertThat(JdbcRowMapper.cursorFromString("not-a-number", Types.BIGINT)).isNull();
    }

    @Test
    void nullAndBlankCursorsMeanNothingHasBeenSyncedYet() {
        assertThat(JdbcRowMapper.cursorToString(null)).isNull();
        assertThat(JdbcRowMapper.cursorFromString(null, Types.TIMESTAMP)).isNull();
        assertThat(JdbcRowMapper.cursorFromString("   ", Types.TIMESTAMP)).isNull();
    }

    @Test
    void aLegacyLocalTimestampStringIsStillReadable() {
        // Tolerating the older format means an upgrade does not force a full reload.
        Object restored = JdbcRowMapper.cursorFromString("2026-06-01 12:00:00", Types.TIMESTAMP);
        assertThat(restored).isInstanceOf(Timestamp.class);
    }

    @Test
    void sqlServerRowversionWatermarkRoundTripsAsHex() {
        // SQL Server rowversion/timestamp columns arrive from mssql-jdbc as byte[].
        // Stored as lowercase hex, rebound back as the exact same byte[] for the next cycle.
        byte[] watermark = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, (byte) 0xab, (byte) 0xcd};
        String hex = JdbcRowMapper.cursorToString(watermark);
        assertThat(hex).isEqualTo("000000000001abcd");

        Object restored = JdbcRowMapper.cursorFromString(hex, Types.BINARY);
        assertThat(restored).isInstanceOf(byte[].class);
        assertThat((byte[]) restored).isEqualTo(watermark);
    }

    @Test
    void nonHexBinaryCursorIsRejectedAsAbsent() {
        // A corrupted watermark (odd-length hex, or non-hex characters) must fail safe
        // rather than throw mid-cycle.
        assertThat(JdbcRowMapper.cursorFromString("0000abc", Types.BINARY)).isNull();
        assertThat(JdbcRowMapper.cursorFromString("zz0011", Types.BINARY)).isNull();
    }
}
