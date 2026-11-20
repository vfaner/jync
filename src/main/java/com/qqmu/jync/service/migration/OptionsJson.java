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

    public static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    public static long longValue(JsonNode node, String field, long fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asLong(fallback);
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String errorKey) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(errorKey);
        }
    }
}
