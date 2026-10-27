package com.qqmu.jync.service.auth.oa;

/**
 * Field normalisation shared by the OA clients and the config service: every user-typed
 * string is trimmed, and a blank one is treated as absent so it falls back to a default
 * instead of being stored as a value.
 */
final class OaText {

    private OaText() {
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String orDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }
}
