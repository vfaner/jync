package com.qqmu.jync.util;

/**
 * Elapsed time as a compact, human-readable label: {@code 99550 ms} reads as {@code 1m 39s}.
 *
 * <p>Unit letters are language-neutral on purpose. The same label feeds the change-log table
 * in both the Chinese and the English UI, and the task summary it also appears in is assembled
 * in the background where no request locale exists — Chinese unit words there would leak into
 * the English UI (exactly what {@code EnglishUiLeakTest} sweeps for).
 *
 * <p>Milliseconds survive only below one second: those are the "created table 8ms" rows, and
 * rendering them as {@code 0s} would lose the only signal they carry. Every larger unit is
 * truncated, never rounded: this is time already spent, so it must not report a value that has
 * not happened yet.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    /** @param millis elapsed milliseconds; zero or negative renders as {@code 0 ms} */
    public static String format(long millis) {
        if (millis < 1000) {
            return Math.max(millis, 0) + " ms";
        }
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return days + "d" + part(hours % 24, "h") + part(minutes % 60, "m");
        }
        if (hours > 0) {
            return hours + "h" + part(minutes % 60, "m");
        }
        if (minutes > 0) {
            return minutes + "m" + part(seconds % 60, "s");
        }
        return seconds + "s";
    }

    /** Zero parts are dropped: {@code 2h} beats {@code 2h 0m}. */
    private static String part(long value, String unit) {
        return value == 0 ? "" : " " + value + unit;
    }
}
