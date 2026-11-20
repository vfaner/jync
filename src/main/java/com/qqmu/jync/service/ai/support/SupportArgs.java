package com.qqmu.jync.service.ai.support;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Reads tool arguments with one failure mode the model can understand. */
public final class SupportArgs {

    public static final String BAD_ARGS = "error.support.tool.args";

    private SupportArgs() {
    }

    public static void onlyKeys(JsonNode args, Set<String> allowed) {
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException(BAD_ARGS);
        }
        for (var entry : args.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException(BAD_ARGS);
            }
        }
    }

    public static String optionalText(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asString("").trim();
        return text.isEmpty() ? null : text;
    }

    public static String requireText(JsonNode args, String field, int maxLength) {
        String text = optionalText(args, field);
        if (text == null || text.length() > maxLength) {
            throw new IllegalArgumentException(BAD_ARGS);
        }
        return text;
    }

    public static Long requireLong(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (!node.isNumber()) {
            throw new IllegalArgumentException(BAD_ARGS);
        }
        return node.asLong();
    }

    public static Long optionalLong(JsonNode args, String field) {
        JsonNode node = args.path(field);
        return node.isNumber() ? node.asLong() : null;
    }

    /** Parses a control action, refusing anything outside the tool's allowed set. */
    public static SupportActionStore.Action action(String raw,
                                                   Set<SupportActionStore.Action> allowed) {
        try {
            SupportActionStore.Action action = SupportActionStore.Action.valueOf(raw);
            if (!allowed.contains(action)) {
                throw new IllegalArgumentException(BAD_ARGS);
            }
            return action;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(BAD_ARGS);
        }
    }
}
