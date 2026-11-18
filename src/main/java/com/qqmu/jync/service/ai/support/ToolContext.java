package com.qqmu.jync.service.ai.support;

import java.util.HashMap;
import java.util.Map;

import com.qqmu.jync.service.auth.AccessGuard;

/** Per-conversation state: who is asking, in what language, and how often costly tools ran. */
public final class ToolContext {

    private final AccessGuard actor;
    private final String language;
    private final Map<String, Integer> counts = new HashMap<>();

    public ToolContext(AccessGuard actor) {
        this(actor, "zh");
    }

    public ToolContext(AccessGuard actor, String language) {
        this.actor = actor;
        this.language = language == null || language.isBlank() ? "zh" : language;
    }

    public AccessGuard actor() {
        return actor;
    }

    public String language() {
        return language;
    }

    public int count(String key) {
        return counts.getOrDefault(key, 0);
    }

    public int increment(String key) {
        int next = count(key) + 1;
        counts.put(key, next);
        return next;
    }
}
