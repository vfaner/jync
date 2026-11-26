package com.qqmu.jync.util;

import java.util.Locale;

/**
 * Human-readable byte sizes for server-rendered pages, matching the metrics chart's
 * formatter: 16384 → "16 KB", 1 GB 2 MB → "1 GB 2 MB". Raw byte counts read as database
 * internals and hide the magnitude.
 */
public final class SizeFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private SizeFormat() {
    }

    public static String bytes(long value) {
        long n = Math.max(0L, value);
        int unit = 0;
        while (n >= 1024L && unit < UNITS.length - 1) {
            n /= 1024L;
            unit++;
        }
        if (unit == 0) {
            return n + " B";
        }
        // Keep the remainder in the next lower unit, but not a second fractional tier.
        long whole = n;
        long remainder = Math.max(0L, value) - whole * pow1024(unit);
        StringBuilder text = new StringBuilder()
                .append(String.format(Locale.ROOT, "%,d", whole))
                .append(' ').append(UNITS[unit]);
        if (remainder > 0) {
            long lower = remainder / pow1024(unit - 1);
            if (lower > 0) {
                text.append(' ').append(String.format(Locale.ROOT, "%,d", lower))
                        .append(' ').append(UNITS[unit - 1]);
            }
        }
        return text.toString();
    }

    private static long pow1024(int exponent) {
        long result = 1L;
        for (int i = 0; i < exponent; i++) {
            result *= 1024L;
        }
        return result;
    }
}
