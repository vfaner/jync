package com.qqmu.jync.service.auth.oa;

import com.fasterxml.jackson.databind.JsonNode;

/** Dot-path lookup and accepted-value matching over a JSON response. */
public final class JsonFieldReader {

    private JsonFieldReader() {
    }

    /**
     * Dot path lookup ({@code data.user}). Missing nodes, nulls and containers all count as
     * absent — only a scalar can name a user or carry a success marker.
     */
    public static String textAt(JsonNode root, String path) {
        if (root == null || path == null) {
            return null;
        }
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(part);
        }
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** True when the field resolves to one of the comma-separated accepted values. */
    public static boolean matches(JsonNode root, String field, String commaSeparatedValues) {
        String path = trimToNull(field);
        String values = trimToNull(commaSeparatedValues);
        if (path == null || values == null) {
            return false;
        }
        String actual = textAt(root, path);
        if (actual == null) {
            return false;
        }
        for (String accepted : values.split(",")) {
            if (accepted.trim().equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
