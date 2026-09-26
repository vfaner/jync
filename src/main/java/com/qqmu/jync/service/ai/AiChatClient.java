package com.qqmu.jync.service.ai;

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
import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;

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
                // One parse: both the reply text and the served-model field are read off
                // the same tree, and a completion body can be large.
                JsonNode root = parseOrNull(response.body());
                return ChatResult.success(extractText(protocol, root), servedModel(root),
                        endpoint, model, ms);
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

    /**
     * Sends one streaming completion request, handing each text delta to {@code sink} as it
     * arrives, and returns the accumulated reply in the usual {@link ChatResult}.
     *
     * <p>Exists because a conversion draft is as long as the procedure it rewrites: waiting for
     * the whole body in one buffer means the reviewer watches a spinner for the entire
     * generation. Streaming moves the first visible text to the first network chunk.
     *
     * <p>The request timeout bounds the header wait; for the body the same value is used as a
     * stall limit between consecutive lines. It is deliberately not an absolute deadline: a
     * gateway that buffers the whole generation goes silent for exactly that long, and failing
     * it would break drafts the buffered call completes.
     */
    public ChatResult stream(AiProvider provider, String apiKey, String system, String user,
                             int maxTokens, java.util.function.Consumer<String> sink) {
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
        // How long the stream may go quiet before it is considered dead. Not an absolute
        // deadline: see the read loop for why the first-byte wait is deliberately unbounded.
        long deadline = timeout.toMillis();

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = buildRequest(protocol, endpoint, model, apiKey, timeout,
                    system, user, maxTokens, true);
            HttpResponse<java.io.InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() / 100 != 2) {
                String detail = extractError(readAll(response.body()));
                log.warn("AI stream to '{}' failed: HTTP {} {}",
                        provider.getName(), response.statusCode(), detail);
                return ChatResult.failure("HTTP " + response.statusCode()
                        + (detail.isEmpty() ? "" : " — " + detail), endpoint, model,
                        System.currentTimeMillis() - start);
            }

            StringBuilder text = new StringBuilder();
            String[] served = {""};
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                String event = null;
                // Some gateways accept stream:true and answer with one buffered completion
                // anyway, often only after the whole generation. The first meaningful line
                // tells the two apart; from then on the body is collected whole and parsed
                // like a non-streaming reply, so such a provider degrades to the old
                // behaviour instead of losing the reply entirely.
                StringBuilder rawBody = null;
                // Stall clock, not an absolute one: a gateway that buffers the generation
                // stays silent for exactly as long as the generation takes, and cutting that
                // silence would fail drafts the buffered call always completed. Only a stream
                // that goes quiet MID-flight is broken, and only that is cut here. The wait
                // for the very first byte stays bounded by the request timeout alone.
                long lastLine = -1;
                while ((line = reader.readLine()) != null) {
                    long now = System.currentTimeMillis();
                    if (lastLine >= 0 && now - lastLine > deadline) {
                        return ChatResult.failure("Stream stalled for over "
                                + timeout.getSeconds() + "s", endpoint, model, now - start);
                    }
                    lastLine = now;
                    if (rawBody != null) {
                        rawBody.append(line).append('\n');
                        continue;
                    }
                    if (line.isEmpty()) {
                        event = null;
                        continue;
                    }
                    if (!line.startsWith("data:") && !line.startsWith("event:")
                            && !line.startsWith(":")) {
                        rawBody = new StringBuilder(line).append('\n');
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data) || "message_stop".equals(event)) {
                        break;
                    }
                    JsonNode root = parseOrNull(data);
                    if (root == null) {
                        continue;
                    }
                    if (served[0].isEmpty()) {
                        served[0] = servedModel(root);
                    }
                    // Chunk shape is detected, not assumed: gateways are routinely configured
                    // with one protocol and answer with the other's events.
                    String delta = "";
                    JsonNode choice = root.path("choices").path(0);
                    if (!choice.isMissingNode()) {
                        delta = choice.path("delta").path("content").asText("");
                        if (delta.isEmpty()) {
                            // Some compatible endpoints send the whole text as a message chunk.
                            delta = choice.path("message").path("content").asText("");
                        }
                    } else if ("message_stop".equals(root.path("type").asText(""))) {
                        break;
                    } else if ("content_block_delta".equals(root.path("type").asText(""))
                            && "text_delta".equals(root.path("delta").path("type").asText(""))) {
                        delta = root.path("delta").path("text").asText("");
                    }
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        sink.accept(delta);
                    }
                }
                if (rawBody != null) {
                    // Buffered reply from a gateway that ignored stream=true: one sink call,
                    // same contract as complete() would have given.
                    JsonNode root = parseOrNull(rawBody.toString());
                    String whole = extractText(protocol, root);
                    if (!whole.isEmpty()) {
                        sink.accept(whole);
                    }
                    return ChatResult.success(whole, servedModel(root), endpoint, model,
                            System.currentTimeMillis() - start);
                }
            }
            return ChatResult.success(text.toString(), served[0], endpoint, model,
                    System.currentTimeMillis() - start);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatResult.failure("Interrupted", endpoint, model,
                    System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            log.warn("AI stream to '{}' failed: {}", provider.getName(), e.toString());
            return ChatResult.failure(e.getMessage() == null ? e.toString() : e.getMessage(),
                    endpoint, model, System.currentTimeMillis() - start);
        }
    }

    /** Drains an error body; only reached on a non-2xx stream response. */
    private String readAll(java.io.InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpRequest buildRequest(AiProtocol protocol, String endpoint, String model,
                                     String apiKey, Duration timeout,
                                     String system, String user, int maxTokens) {
        return buildRequest(protocol, endpoint, model, apiKey, timeout, system, user, maxTokens,
                false);
    }

    private HttpRequest buildRequest(AiProtocol protocol, String endpoint, String model,
                                     String apiKey, Duration timeout,
                                     String system, String user, int maxTokens, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (stream) {
            body.put("stream", true);
        }

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
                // A few gateways only switch to SSE on the Accept header rather than the
                // stream flag; the majors ignore Accept entirely, so asking costs nothing.
                .header("Accept", stream ? "text/event-stream" : "application/json");

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

    /** Parses the response body once; null when it is blank or not JSON. */
    private JsonNode parseOrNull(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(responseBody);
        } catch (IOException e) {
            log.debug("Could not parse AI response body as JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Pulls the reply text out of whichever response shape the protocol uses. */
    private String extractText(AiProtocol protocol, JsonNode root) {
        if (root == null) {
            return "";
        }
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
    }

    /** The model the endpoint says answered, which gateways often remap from the alias sent. */
    private String servedModel(JsonNode root) {
        return root == null ? "" : root.path("model").asText("");
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
