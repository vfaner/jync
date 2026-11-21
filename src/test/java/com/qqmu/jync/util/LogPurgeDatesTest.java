package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * The purge date rule: the selected day is included, today is not selectable, and the cutoff
 * instant is the start of the following day in the server's zone.
 */
class LogPurgeDatesTest {

    private final ZoneId zone = ZoneId.systemDefault();

    @Test
    void yesterdayIsTheNewestSelectableDay() {
        assertThat(LogPurgeDates.maxSelectable()).isEqualTo(LocalDate.now(zone).minusDays(1));
    }

    @Test
    void theCutoffIncludesTheWholeSelectedDay() {
        LocalDate day = LocalDate.now(zone).minusDays(3);

        assertThat(LogPurgeDates.cutoffFor(day))
                .isEqualTo(day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Test
    void todayAndLaterAreRefused() {
        assertThatThrownBy(() -> LogPurgeDates.cutoffFor(LocalDate.now(zone)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LogPurgeDates.INVALID_DATE);
        assertThatThrownBy(() -> LogPurgeDates.cutoffFor(LocalDate.now(zone).plusDays(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LogPurgeDates.INVALID_DATE);
    }

    @Test
    void aNullDayIsRefused() {
        assertThatThrownBy(() -> LogPurgeDates.cutoffFor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LogPurgeDates.INVALID_DATE);
    }
}
