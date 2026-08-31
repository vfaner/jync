package com.synctool.service.sync;

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
