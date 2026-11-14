package com.qqmu.jync.service.auth.oa;

import com.qqmu.jync.service.auth.ExternalIdentity;

import tools.jackson.databind.JsonNode;

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
        if (node == null || node.isNull() || node.isContainer()) {
            return null;
        }
        String text = node.asString();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** True when the field resolves to one of the comma-separated accepted values. */
    public static boolean matches(JsonNode root, String field, String commaSeparatedValues) {
        String path = OaText.trimToNull(field);
        String values = OaText.trimToNull(commaSeparatedValues);
        if (path == null || values == null) {
            return false;
        }
        // Same trim-accept-both-sides rule as the configured admin list; one implementation.
        return ExternalIdentity.matches(textAt(root, path), values);
    }
}
