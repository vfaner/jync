package com.qqmu.jync.model;

import lombok.Getter;

/**
 * Wire protocols for an AI provider endpoint.
 *
 * <p>Only the two wire shapes that matter in practice are modelled: OpenAI's and Anthropic's.
 * Nearly every self-hosted or domestic endpoint speaks the OpenAI chat-completions shape, so
 * {@link #OPENAI} doubles as "OpenAI-compatible" and is the right choice for an intranet
 * inference service. The two Ollama entries are two routes into the same local server, each
 * described below.
 *
 * <p>{@link #OLLAMA} is a named preset, not a third protocol: it answers on Ollama's
 * OpenAI-compatibility endpoint, so its {@code chatPath} and wire behaviour are identical to
 * {@link #OPENAI}. It exists so the form can prefill a localhost address and offer the fields
 * that make sense for a local model.
 *
 * <p>{@link #OLLAMA_NATIVE} is Ollama's own {@code /api/chat}. Some deployments only expose that
 * one — a proxy that whitelists {@code /api/*}, or a compatibility layer too old to be trusted —
 * so it is a separate member rather than a change to {@link #OLLAMA}: those rows are already
 * saved with a base URL ending in {@code /v1}, and pointing them at {@code /v1/api/chat} would
 * 404. Its wire shape differs from OpenAI's in three places, all handled in
 * {@code AiChatClient}: {@code options.num_predict} instead of {@code max_tokens}, the reply in
 * {@code message.content}, and a stream that is one JSON object per line with no {@code data:}
 * prefix.
 *
 * <p>{@code chatPath} is appended to the configured base URL only when the base URL does
 * not already end with it, because vendors disagree about whether the {@code /v1} segment
 * belongs to the base URL or to the path.
 */
@Getter
public enum AiProtocol {

    OPENAI("ai.protocol.OPENAI", "https://api.openai.com/v1", "/chat/completions"),

    ANTHROPIC("ai.protocol.ANTHROPIC", "https://api.anthropic.com", "/v1/messages"),

    /** Ollama 本机默认端口;走的是它的 OpenAI 兼容端点,不是原生 /api/chat。 */
    OLLAMA("ai.protocol.OLLAMA", "http://localhost:11434/v1", "/chat/completions"),

    /** Ollama 原生端点;baseUrl 不带 /v1,流式是 NDJSON。 */
    OLLAMA_NATIVE("ai.protocol.OLLAMA_NATIVE", "http://localhost:11434", "/api/chat");

    /**
     * i18n key for the human label, not the label itself.
     *
     * <p>It used to be a hardcoded string, and "OpenAI 兼容" / "Ollama（本地）" were rendered
     * verbatim into the English UI — a leak the {@code ??}-based bundle guards cannot see,
     * because a literal resolves fine and never turns into {@code ??key??}. Keeping only the
     * key here means the label can only come from a bundle.
     */
    private final String messageKey;

    /** Prefilled in the form when the protocol is selected; almost always replaced. */
    private final String defaultBaseUrl;

    private final String chatPath;

    AiProtocol(String messageKey, String defaultBaseUrl, String chatPath) {
        this.messageKey = messageKey;
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
