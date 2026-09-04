package com.synctool.service.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * One HTTP call to a chat-completion endpoint, in either supported protocol.
 *
 * <p>Both the connectivity probe and the conversion assistant go through here so the two cannot
 * drift apart: a base URL that the probe reports as reachable must be the URL the assistant
 * actually posts to, and the header that authenticates one must authenticate the other. The
 * protocols differ in three ways that matter and are all handled in this class — the auth header
 * name, where the system prompt goes, and where the reply text sits in the response.
 *
 * <p>Failures are returned, not thrown. An endpoint's own error body ("model not found",
 * "insufficient quota") names the problem far better than any exception this code could invent,
 * so it is passed through to the caller verbatim.
 *
 * <p>The API key is written to exactly one place — the outbound request header. It is never
 * logged and never copied into a {@link ChatResult}.
 */
@Component
@Slf4j
public class AiChatClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Cap on how much of an error body is kept, so a stack-trace HTML page cannot fill the UI. */
    private static final int MAX_ERROR_CHARS = 300;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Sends one completion request and waits for the whole reply.
     *
     * @param provider  supplies protocol, base URL, model and timeout
     * @param apiKey    the decrypted key; callers must never pass the stored ciphertext
     * @param system    system prompt, or null for none
     * @param user      user prompt; must not be blank
     * @param maxTokens reply budget
     * @return the outcome, never null
     */
    public ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                               int maxTokens) {
        AiProtocol protocol = provider.getProtocol() == null
                ? AiProtocol.OPENAI : provider.getProtocol();
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();

        if (model.isEmpty()) {
            return ChatResult.failure("error.ai.model.required", endpoint, model, 0);
        }

        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    // A redirect would silently drop the auth header on a cross-host hop, so
                    // report it instead and let the user configure the final URL.
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            HttpRequest request = buildRequest(protocol, endpoint, model, apiKey, timeout,
                    system, user, maxTokens);
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = System.currentTimeMillis() - start;

            if (response.statusCode() / 100 == 2) {
                return ChatResult.success(extractText(protocol, response.body()),
                        servedModel(response.body()), endpoint, model, ms);
            }
            String detail = extractError(response.body());
            log.warn("AI request to '{}' failed: HTTP {} {}",
                    provider.getName(), response.statusCode(), detail);
            return ChatResult.failure("HTTP " + response.statusCode()
                    + (detail.isEmpty() ? "" : " — " + detail), endpoint, model, ms);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatResult.failure("Interrupted", endpoint, model,
                    System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            // Covers DNS failure, connection refused and timeouts -- the common intranet cases.
            log.warn("AI request to '{}' failed: {}", provider.getName(), e.toString());
            return ChatResult.failure(e.getMessage() == null ? e.toString() : e.getMessage(),
                    endpoint, model, System.currentTimeMillis() - start);
        }
    }

    private HttpRequest buildRequest(AiProtocol protocol, String endpoint, String model,
                                     String apiKey, Duration timeout,
                                     String system, String user, int maxTokens) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        boolean anthropic = protocol == AiProtocol.ANTHROPIC;
        boolean hasSystem = system != null && !system.isBlank();

        // Anthropic takes the system prompt as a top-level field and rejects a system role in
        // the message list; OpenAI-compatible endpoints expect it as the first message.
        if (hasSystem && anthropic) {
            body.put("system", system);
        }
        ArrayNode messages = body.putArray("messages");
        if (hasSystem && !anthropic) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", user);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (anthropic) {
            builder.header("x-api-key", apiKey == null ? "" : apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            // OpenAI-compatible endpoints universally accept the bearer form, including the
            // self-hosted ones that ignore the key entirely.
            builder.header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        }

        return builder.POST(HttpRequest.BodyPublishers.ofString(
                body.toString(), StandardCharsets.UTF_8)).build();
    }

    /** Pulls the reply text out of whichever response shape the protocol uses. */
    private String extractText(AiProtocol protocol, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (protocol == AiProtocol.ANTHROPIC) {
                // content is a list of typed blocks; only the text ones carry the reply, and a
                // response may legitimately open with a non-text block.
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : root.path("content")) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText(""));
                    }
                }
                return sb.toString();
            }
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            log.debug("Could not parse AI response body as JSON: {}", e.getMessage());
            return "";
        }
    }

    /** The model the endpoint says answered, which gateways often remap from the alias sent. */
    private String servedModel(String responseBody) {
        try {
            return mapper.readTree(responseBody).path("model").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    /** Pulls the human-readable part out of an error body, falling back to a truncated body. */
    private String extractError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            for (String path : new String[] {"error", "message"}) {
                JsonNode node = root.path(path);
                if (node.isTextual() && !node.asText().isBlank()) {
                    return truncate(node.asText());
                }
                JsonNode nested = node.path("message");
                if (nested.isTextual() && !nested.asText().isBlank()) {
                    return truncate(nested.asText());
                }
            }
        } catch (IOException e) {
            // Not JSON: an HTML error page from a reverse proxy is itself the useful signal.
        }
        return truncate(responseBody);
    }

    private String truncate(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > MAX_ERROR_CHARS
                ? flat.substring(0, MAX_ERROR_CHARS) + "..." : flat;
    }

    /** Outcome of one chat call. */
    @Getter
    public static class ChatResult {
        private final boolean success;
        /** The model's reply, empty on failure. */
        private final String text;
        /** Human-readable status; on failure this is the endpoint's own error. */
        private final String message;
        private final String endpoint;
        /** Model named by the response, which may differ from the one requested. */
        private final String servedModel;
        private final String requestedModel;
        private final long elapsedMs;

        private ChatResult(boolean success, String text, String message, String endpoint,
                           String servedModel, String requestedModel, long ms) {
            this.success = success;
            this.text = text;
            this.message = message;
            this.endpoint = endpoint;
            this.servedModel = servedModel;
            this.requestedModel = requestedModel;
            this.elapsedMs = ms;
        }

        static ChatResult success(String text, String servedModel, String endpoint,
                                  String requestedModel, long ms) {
            return new ChatResult(true, text, "OK", endpoint, servedModel, requestedModel, ms);
        }

        static ChatResult failure(String message, String endpoint, String requestedModel, long ms) {
            return new ChatResult(false, "", message, endpoint, "", requestedModel, ms);
        }

        /** The model that answered, falling back to the one requested when unreported. */
        public String effectiveModel() {
            return servedModel == null || servedModel.isBlank() ? requestedModel : servedModel;
        }
    }
}
