package com.synctool.model;

import lombok.Getter;

/**
 * Wire protocols for an AI provider endpoint.
 *
 * <p>Only the two that matter in practice are modelled. Nearly every self-hosted or
 * domestic endpoint speaks the OpenAI chat-completions shape, so {@link #OPENAI} doubles
 * as "OpenAI-compatible" and is the right choice for an intranet inference service.
 *
 * <p>{@code chatPath} is appended to the configured base URL only when the base URL does
 * not already end with it, because vendors disagree about whether the {@code /v1} segment
 * belongs to the base URL or to the path.
 */
@Getter
public enum AiProtocol {

    OPENAI("OpenAI 兼容", "https://api.openai.com/v1", "/chat/completions"),

    ANTHROPIC("Anthropic", "https://api.anthropic.com", "/v1/messages");

    private final String displayName;

    /** Prefilled in the form when the protocol is selected; almost always replaced. */
    private final String defaultBaseUrl;

    private final String chatPath;

    AiProtocol(String displayName, String defaultBaseUrl, String chatPath) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.chatPath = chatPath;
    }

    public static AiProtocol fromName(String name) {
        if (name != null) {
            for (AiProtocol p : values()) {
                if (p.name().equalsIgnoreCase(name.trim())) {
                    return p;
                }
            }
        }
        return OPENAI;
    }

    /**
     * The full endpoint the request is sent to.
     *
     * <p>Echoed back in the test result: when a key is fine but the base URL is missing or
     * doubling a {@code /v1}, the resolved URL is the only thing that makes the 404 obvious.
     */
    public String resolveEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = defaultBaseUrl;
        }
        return base.endsWith(chatPath) ? base : base + chatPath;
    }
}
