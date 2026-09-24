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
}
