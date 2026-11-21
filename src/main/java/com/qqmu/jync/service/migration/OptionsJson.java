package com.qqmu.jync.service.migration;

import java.util.Locale;

import tools.jackson.databind.JsonNode;

/**
 * Lenient field readers shared by the migration options DTOs: an absent or null field falls
 * back to the caller's default, and an enum typo fails with the caller's i18n key instead of
 * silently selecting a destructive default.
 */
public final class OptionsJson {

    private OptionsJson() {
    }

    public static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asString();
    }

    public static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    /**
     * A present numeric option. The form submits values as strings, so a numeric string is
     * accepted; a blank string means "not set" (fallback). Anything else — a typo, an
     * overflowing number — fails instead of silently becoming the fallback, because the
     * fallback for most of these is the behaviour the operator did NOT choose.
     */
    public static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        long parsed = parseLong(value);
        if (parsed == Long.MIN_VALUE) {
            return fallback;   // only reachable for a blank string
        }
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("error.migration.options.invalid");
        }
        return (int) parsed;
    }

    public static long longValue(JsonNode node, String field, long fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        long parsed = parseLong(value);
        return parsed == Long.MIN_VALUE ? fallback : parsed;
    }

    /**
     * The sentinel {@link Long#MIN_VALUE} means "blank string, use the fallback"; a real
     * value equal to it cannot be submitted through the form and would be nonsensical here.
     */
    private static long parseLong(JsonNode value) {
        if (value.isIntegralNumber()) {
            long parsed = value.asLong(Long.MIN_VALUE);
            if (parsed == Long.MIN_VALUE) {
                throw new IllegalArgumentException("error.migration.options.invalid");
            }
            return parsed;
        }
        if (value.isString()) {
            String text = value.asString("").trim();
            if (text.isEmpty()) {
                return Long.MIN_VALUE;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("error.migration.options.invalid");
            }
        }
        throw new IllegalArgumentException("error.migration.options.invalid");
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String errorKey) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(errorKey);
        }
    }
}
