package com.qqmu.jync.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The date rule shared by the change-log and audit-log purges.
 *
 * <p>A purge removes everything on or before the selected day, so the cutoff instant is the
 * start of the day <em>after</em> it. The selected day may be at most yesterday: today's rows
 * always survive, which is what makes "delete by date" structurally unable to wipe a log in
 * one go — the product deliberately offers no clear-all.
 */
public final class LogPurgeDates {

    public static final String INVALID_DATE = "log.purge.date.invalid";

    private LogPurgeDates() {
    }

    /** The newest day the picker may offer: yesterday, in the server's zone. */
    public static LocalDate maxSelectable() {
        return LocalDate.now(ZoneId.systemDefault()).minusDays(1);
    }

    /**
     * The exclusive upper instant for a purge of {@code day} (inclusive of that whole day).
     *
     * @throws IllegalArgumentException with {@link #INVALID_DATE} when the day is null,
     *     unparseable by the caller, or today/later
     */
    public static Instant cutoffFor(LocalDate day) {
        if (day == null || !day.isBefore(LocalDate.now(ZoneId.systemDefault()))) {
            throw new IllegalArgumentException(INVALID_DATE);
        }
        return day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
