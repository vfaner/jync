package com.qqmu.jync.service.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AiReasoningEffort;
import com.qqmu.jync.util.AiEndpointValidator;

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

    /**
     * One client for every call. An {@link HttpClient} owns its own selector and thread pool;
     * building one per request spins those up and discards them, which both costs latency and
     * leaks threads until GC. The connection timeout is therefore fixed here; the per-provider
     * timeout still applies to each request (set on the {@link HttpRequest} itself), and redirects
     * are never followed so a cross-host hop cannot silently shed the auth header.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Abandons a stream that goes silent for longer than its budget.
     *
     * <p>Its own daemon thread because the read is blocking and the JDK client offers no read
     * timeout: {@code HttpRequest.timeout} stops at the response headers, so an upstream that
     * sends one event and then neither data nor a newline pins the calling thread — on the draft
     * endpoints, one of four pool threads — until the socket dies. Blocked reads are only
     * interruptible by closing the stream, which is what the watchdog does.
     */
    private static final ScheduledExecutorService STALL_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-stream-watchdog");
                t.setDaemon(true);
                return t;
            });

    /**
     * Ceiling on the wait before the first body line arrives. Generous on purpose: a gateway that
     * buffers the whole generation is legitimately silent for as long as it generates. It matches
     * the SSE emitter's own ten-minute limit, past which no client is listening anyway.
     */
    private static final long FIRST_LINE_CEILING_MS = 10 * 60 * 1000L;

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
        return complete(provider, apiKey, system, user, maxTokens, effortOf(provider));
    }

    /**
     * Probes an endpoint with the smallest completion that still proves the three things that
     * actually go wrong — reachability, the key, and the model name.
     *
     * <p>Exists as its own entry point for one reason: it must never carry the provider's
     * reasoning setting. The request is {@code max_tokens: 1}, and an Anthropic thinking budget
     * has to be strictly smaller than {@code max_tokens}, so a probe that inherited the setting
     * would come back as the endpoint's own 400 — while "test before saving" is the whole point
     * of this page. A local reasoning model would also spend its single token thinking and then
     * answer with nothing. Everything else about the request — URL, headers, body shape, parsing
     * — still goes through the same code as a real conversion, so a probe cannot pass while real
     * requests fail.
     */
    public ChatResult probe(AiProvider provider, String apiKey) {
        return complete(provider, apiKey, null, "ping", 1, AiReasoningEffort.DEFAULT);
    }

    /**
     * @param effort overrides the provider's stored setting; used by {@link #probe} to opt out
     */
    ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                        int maxTokens, AiReasoningEffort effort) {
        AiProtocol protocol = provider.getProtocol() == null
                ? AiProtocol.OPENAI : provider.getProtocol();
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();

        if (model.isEmpty()) {
            return ChatResult.failure("error.ai.model.required", endpoint, model, 0);
        }
        try {
            AiEndpointValidator.validateBaseUrl(provider.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return ChatResult.failure(e.getMessage(), endpoint, model, 0);
        }

        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds());

        try {
            HttpClient client = HTTP_CLIENT;

            // The base URL was validated above; the JDK client re-resolves the host when it
            // connects. Pinning the validated IP here is not possible through HttpRequest:
            // "Host" is a restricted header, so rewriting the URI and restoring the host via
            // a header throws IllegalArgumentException before any byte is sent.
            HttpRequest request = buildRequest(protocol, endpoint, model, apiKey, timeout,
                    system, user, maxTokens, false, effort);

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
        return stream(provider, apiKey, system, user, maxTokens, sink, effortOf(provider));
    }

    /** @param effort overrides the provider's stored setting; see {@link #complete} */
    ChatResult stream(AiProvider provider, String apiKey, String system, String user,
                      int maxTokens, java.util.function.Consumer<String> sink,
                      AiReasoningEffort effort) {
        AiProtocol protocol = provider.getProtocol() == null
                ? AiProtocol.OPENAI : provider.getProtocol();
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();

        if (model.isEmpty()) {
            return ChatResult.failure("error.ai.model.required", endpoint, model, 0);
        }
        try {
            AiEndpointValidator.validateBaseUrl(provider.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return ChatResult.failure(e.getMessage(), endpoint, model, 0);
        }
        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds());
        // How long the stream may go quiet before it is considered dead. Not an absolute
        // deadline: see the watchdog below for why the first-line wait has its own, larger budget.
        long deadline = timeout.toMillis();
        // Set by the watchdog when it abandons a silent stream, so the failure reported below
        // names the real cause instead of the driver's "Stream closed".
        AtomicReference<String> stalledFor = new AtomicReference<>();

        try {
            HttpClient client = HTTP_CLIENT;
            // See complete(): the base URL was validated above, and pinning the resolved IP
            // through the restricted "Host" header is not possible with the JDK client.
            HttpRequest request = buildRequest(protocol, endpoint, model, apiKey, timeout,
                    system, user, maxTokens, true, effort);

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

            InputStream body = response.body();
            // Activity clock for the watchdog: a blocking read cannot notice its own silence.
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean firstLineSeen = new AtomicBoolean(false);
            long tickMs = Math.max(200L, Math.min(1000L, deadline / 4));
            ScheduledFuture<?> watchdog = STALL_WATCHDOG.scheduleAtFixedRate(() -> {
                long budget = firstLineSeen.get() ? deadline : FIRST_LINE_CEILING_MS;
                if (System.currentTimeMillis() - lastActivity.get() > budget
                        && stalledFor.compareAndSet(null,
                                "Stream stalled for over " + (budget / 1000) + "s")) {
                    // The only way to interrupt a read blocked in the socket.
                    try {
                        body.close();
                    } catch (IOException ignored) {
                        // Already closed; the read was unblocked by something else.
                    }
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);

            StringBuilder text = new StringBuilder();
            String[] served = {""};
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
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
                    firstLineSeen.set(true);
                    lastActivity.set(now);
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
                    if (protocol == AiProtocol.OLLAMA_NATIVE) {
                        // 原生流式是 NDJSON:一行一个独立对象,没有 data: 前缀。必须走在
                        // 下面"裸行 = 网关把整段缓冲了"的判定之前 —— 否则整个流会被收进
                        // rawBody,多行 JSON 拼起来解析失败,回复整个变空。
                        JsonNode chunk = parseOrNull(line);
                        if (chunk == null) {
                            continue;
                        }
                        if (served[0].isEmpty()) {
                            served[0] = servedModel(chunk);
                        }
                        String piece = chunk.path("message").path("content").asString("");
                        if (!piece.isEmpty()) {
                            text.append(piece);
                            sink.accept(piece);
                        }
                        if (chunk.path("done").asBoolean(false)) {
                            break;
                        }
                        continue;
                    }
                    if (!line.startsWith("data:") && !line.startsWith("event:")
                            && !line.startsWith(":")) {
                        // A buffered reply is one JSON document and nothing else. Any other
                        // unrecognized line — a standard SSE `id:`/`retry:` field, a
                        // keep-alive token, a proxy's stray line — used to flip the whole
                        // stream into this mode, after which the reply was parsed out of a
                        // fragment that is not JSON and came back empty. Once a delta has
                        // arrived the body is SSE by proof, not by guess.
                        String head = line.stripLeading();
                        if (text.length() == 0
                                && (head.startsWith("{") || head.startsWith("["))) {
                            rawBody = new StringBuilder(line).append('\n');
                        }
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
                        delta = choice.path("delta").path("content").asString("");
                        if (delta.isEmpty()) {
                            // Some compatible endpoints send the whole text as a message chunk.
                            delta = choice.path("message").path("content").asString("");
                        }
                    } else if ("message_stop".equals(root.path("type").asString(""))) {
                        break;
                    } else if ("content_block_delta".equals(root.path("type").asString(""))
                            && "text_delta".equals(root.path("delta").path("type").asString(""))) {
                        delta = root.path("delta").path("text").asString("");
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
            } finally {
                watchdog.cancel(false);
            }
            return ChatResult.success(text.toString(), served[0], endpoint, model,
                    System.currentTimeMillis() - start);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatResult.failure("Interrupted", endpoint, model,
                    System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            String stallMessage = stalledFor.get();
            if (stallMessage != null) {
                // The watchdog closed the stream; report the cause, not "Stream closed".
                log.warn("AI stream to '{}' stalled: {}", provider.getName(), stallMessage);
                return ChatResult.failure(stallMessage, endpoint, model,
                        System.currentTimeMillis() - start);
            }
            log.warn("AI stream to '{}' failed: {}", provider.getName(), e.toString());
            return ChatResult.failure(e.getMessage() == null ? e.toString() : e.getMessage(),
                    endpoint, model, System.currentTimeMillis() - start);
        }
    }

    /**
     * One streaming round of a tool conversation.
     *
     * <p>Text is handed to {@code sink} the moment each fragment arrives, exactly like
     * {@link #stream}. Tool calls are accumulated across the round and returned; the caller
     * executes them and calls again with the results appended as {@link Turn}s.
     *
     * <p>Distinct from {@link #stream} on purpose: the conversion assistant's contract must not
     * change, and this method owns the extra wire shapes tools introduce — fragmented
     * {@code tool_calls} arguments, Anthropic's content blocks, Ollama's object arguments.
     *
     * @return the round's text, any calls the model asked for, and whether the endpoint looked
     *     like it rejected the very idea of tools (a 4xx naming tools), so the caller can retry
     *     without them instead of surfacing a failure
     */
    public ToolChatResult converseStream(AiProvider provider, String apiKey, String system,
                                         List<Turn> turns, List<ToolSpec> tools, int maxTokens,
                                         java.util.function.Consumer<String> sink) {
        AiProtocol protocol = provider.getProtocol() == null
                ? AiProtocol.OPENAI : provider.getProtocol();
        if (protocol == AiProtocol.ANTHROPIC) {
            return converseAnthropic(provider, apiKey, system, turns, tools, maxTokens, sink);
        }
        if (protocol == AiProtocol.OLLAMA_NATIVE) {
            return converseOllamaNative(provider, apiKey, system, turns, tools, maxTokens, sink);
        }
        return converseOpenAi(provider, protocol, apiKey, system, turns, tools, maxTokens, sink);
    }

    private ToolChatResult converseOpenAi(AiProvider provider, AiProtocol protocol,
                                          String apiKey, String system, List<Turn> turns,
                                          List<ToolSpec> tools, int maxTokens,
                                          java.util.function.Consumer<String> sink) {
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();
        if (model.isEmpty()) {
            return ToolChatResult.failure("error.ai.model.required", 0, false);
        }
        try {
            AiEndpointValidator.validateBaseUrl(provider.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return ToolChatResult.failure(e.getMessage(), 0, false);
        }
        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds());
        AtomicReference<String> stalledFor = new AtomicReference<>();
        try {
            HttpRequest request = buildConverseRequest(protocol, endpoint, model, apiKey,
                    timeout, system, turns, tools, maxTokens);
            HttpResponse<java.io.InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String detail = extractError(readAll(response.body()));
                boolean toolsRejected = response.statusCode() / 100 == 4 && !tools.isEmpty()
                        && detail.toLowerCase(java.util.Locale.ROOT)
                                .matches(".*(tool|function|does not support).*");
                log.warn("AI tool stream to '{}' failed: HTTP {} {}",
                        provider.getName(), response.statusCode(), detail);
                return ToolChatResult.failure("HTTP " + response.statusCode()
                        + (detail.isEmpty() ? "" : " — " + detail),
                        System.currentTimeMillis() - start, toolsRejected);
            }

            java.io.InputStream body = response.body();
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean firstLineSeen = new AtomicBoolean(false);
            long tickMs = Math.max(200L, Math.min(1000L, timeout.toMillis() / 4));
            ScheduledFuture<?> watchdog = STALL_WATCHDOG.scheduleAtFixedRate(() -> {
                long budget = firstLineSeen.get() ? timeout.toMillis() : FIRST_LINE_CEILING_MS;
                if (System.currentTimeMillis() - lastActivity.get() > budget
                        && stalledFor.compareAndSet(null,
                                "Stream stalled for over " + (budget / 1000) + "s")) {
                    try {
                        body.close();
                    } catch (IOException ignored) {
                        // Already closed.
                    }
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);

            StringBuilder text = new StringBuilder();
            String[] served = {""};
            // Keyed by the wire's index so interleaved or repeated fragments still join once.
            Map<Integer, PartialCall> partials = new TreeMap<>();
            StringBuilder rawBody = null;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    firstLineSeen.set(true);
                    lastActivity.set(System.currentTimeMillis());
                    if (rawBody != null) {
                        rawBody.append(line).append('\n');
                        continue;
                    }
                    if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        // Same buffered-reply detection as stream(): a lone JSON document is
                        // what a gateway that ignored stream=true sends.
                        String head = line.stripLeading();
                        if (text.length() == 0 && partials.isEmpty()
                                && (head.startsWith("{") || head.startsWith("["))) {
                            rawBody = new StringBuilder(line).append('\n');
                        }
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode root = parseOrNull(data);
                    if (root == null) {
                        continue;
                    }
                    if (served[0].isEmpty()) {
                        served[0] = servedModel(root);
                    }
                    JsonNode choice = root.path("choices").path(0);
                    String piece = choice.path("delta").path("content").asString("");
                    if (!piece.isEmpty()) {
                        text.append(piece);
                        sink.accept(piece);
                    }
                    for (JsonNode chunk : choice.path("delta").path("tool_calls")) {
                        int index = chunk.path("index").asInt(0);
                        PartialCall call = partials.computeIfAbsent(index, i -> new PartialCall());
                        String id = chunk.path("id").asString("");
                        if (!id.isEmpty()) {
                            call.id = id;
                        }
                        String name = chunk.path("function").path("name").asString("");
                        if (!name.isEmpty()) {
                            call.name = name;
                        }
                        String args = chunk.path("function").path("arguments").asString("");
                        if (!args.isEmpty()) {
                            call.args.append(args);
                        }
                    }
                }
            } finally {
                watchdog.cancel(false);
            }
            if (rawBody != null) {
                JsonNode root = parseOrNull(rawBody.toString());
                String whole = extractText(protocol, root);
                if (!whole.isEmpty()) {
                    sink.accept(whole);
                }
                return ToolChatResult.success(whole, List.of(), servedModel(root),
                        System.currentTimeMillis() - start);
            }
            List<ToolCall> calls = new ArrayList<>();
            partials.forEach((index, call) -> calls.add(new ToolCall(
                    call.id.isEmpty() ? "call_" + index : call.id,
                    call.name, call.args.toString())));
            return ToolChatResult.success(text.toString(), calls, served[0],
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolChatResult.failure("Interrupted", System.currentTimeMillis() - start, false);
        } catch (IOException | RuntimeException e) {
            String stall = stalledFor.get();
            log.warn("AI tool stream to '{}' failed: {}", provider.getName(),
                    stall == null ? e.toString() : stall);
            return ToolChatResult.failure(stall == null
                    ? (e.getMessage() == null ? e.toString() : e.getMessage()) : stall,
                    System.currentTimeMillis() - start, false);
        }
    }

    /** Accumulator for one streamed tool call; arguments arrive as fragments. */
    private static final class PartialCall {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    /** Drains an error body; only reached on a non-2xx stream response. */
    private String readAll(java.io.InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpRequest buildRequest(AiProtocol protocol, String endpoint, String model,
                                     String apiKey, Duration timeout,
                                     String system, String user, int maxTokens, boolean stream,
                                     AiReasoningEffort effort) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        boolean nativeOllama = protocol == AiProtocol.OLLAMA_NATIVE;
        if (nativeOllama) {
            // 原生形状的两处不同。stream 总是显式发:Ollama 的默认是 true,
            // 省略它等于把一次非流式调用变成 NDJSON 回来,而调用方在等一个完整 JSON。
            body.put("stream", stream);
            body.putObject("options").put("num_predict", maxTokens);
        } else {
            body.put("max_tokens", maxTokens);
            if (stream) {
                body.put("stream", true);
            }
        }
        applyReasoning(protocol, body, effort, maxTokens);

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
                .header("Accept", stream && !nativeOllama
                        ? "text/event-stream" : "application/json");

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

    /**
     * Builds a tool-conversation request. Always streams: the assistant's whole point is showing
     * sentences as they are written.
     */
    private HttpRequest buildConverseRequest(AiProtocol protocol, String endpoint, String model,
                                             String apiKey, Duration timeout, String system,
                                             List<Turn> turns, List<ToolSpec> tools,
                                             int maxTokens) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        boolean nativeOllama = protocol == AiProtocol.OLLAMA_NATIVE;
        if (nativeOllama) {
            body.put("stream", true);
            body.putObject("options").put("num_predict", maxTokens);
        } else {
            body.put("max_tokens", maxTokens);
            body.put("stream", true);
        }
        // No reasoning knob here on purpose: the assistant's answers are short and the extra
        // latency of a thinking model works against a chat panel.
        applyReasoning(protocol, body, AiReasoningEffort.DEFAULT, maxTokens);

        boolean anthropic = protocol == AiProtocol.ANTHROPIC;
        boolean hasSystem = system != null && !system.isBlank();
        if (hasSystem && anthropic) {
            body.put("system", system);
        }
        ArrayNode messages = body.putArray("messages");
        if (hasSystem && !anthropic) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        for (Turn turn : turns) {
            if (anthropic) {
                appendAnthropicMessage(messages, turn);
            } else if (nativeOllama) {
                appendOllamaMessage(messages, turn);
            } else {
                appendOpenAiMessage(messages, turn);
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode defs = body.putArray("tools");
            for (ToolSpec tool : tools) {
                ObjectNode def = defs.addObject();
                if (anthropic) {
                    def.put("name", tool.name());
                    def.put("description", tool.description());
                    def.set("input_schema", mapper.valueToTree(tool.parameters()));
                } else {
                    def.put("type", "function");
                    ObjectNode fn = def.putObject("function");
                    fn.put("name", tool.name());
                    fn.put("description", tool.description());
                    fn.set("parameters", mapper.valueToTree(tool.parameters()));
                }
            }
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", nativeOllama ? "application/json" : "text/event-stream");
        if (anthropic) {
            builder.header("x-api-key", apiKey == null ? "" : apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(
                body.toString(), StandardCharsets.UTF_8)).build();
    }

    private void appendOpenAiMessage(ArrayNode messages, Turn turn) {
        ObjectNode message = messages.addObject();
        if ("tool".equals(turn.role())) {
            message.put("role", "tool");
            message.put("tool_call_id", turn.toolCallId() == null ? "" : turn.toolCallId());
            message.put("content", turn.content() == null ? "" : turn.content());
            return;
        }
        message.put("role", turn.role());
        message.put("content", turn.content() == null ? "" : turn.content());
        if ("assistant".equals(turn.role()) && !turn.toolCalls().isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCall call : turn.toolCalls()) {
                ObjectNode c = calls.addObject();
                c.put("id", call.id());
                c.put("type", "function");
                ObjectNode fn = c.putObject("function");
                fn.put("name", call.name());
                fn.put("arguments", call.argumentsJson());
            }
        }
    }

    /**
     * Anthropic puts everything in typed content blocks: the assistant's tool request is a
     * {@code tool_use} block, and its result comes back as a {@code tool_result} block inside a
     * {@code user} message rather than a {@code tool} role.
     */
    private void appendAnthropicMessage(ArrayNode messages, Turn turn) {
        ObjectNode message = messages.addObject();
        if ("tool".equals(turn.role())) {
            message.put("role", "user");
            ArrayNode blocks = message.putArray("content");
            ObjectNode block = blocks.addObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", turn.toolCallId() == null ? "" : turn.toolCallId());
            block.put("content", turn.content() == null ? "" : turn.content());
            return;
        }
        message.put("role", turn.role());
        ArrayNode blocks = message.putArray("content");
        if (turn.content() != null && !turn.content().isEmpty()) {
            ObjectNode text = blocks.addObject();
            text.put("type", "text");
            text.put("text", turn.content());
        }
        for (ToolCall call : turn.toolCalls()) {
            ObjectNode use = blocks.addObject();
            use.put("type", "tool_use");
            use.put("id", call.id());
            use.put("name", call.name());
            JsonNode input = parseOrNull(call.argumentsJson());
            use.set("input", input == null ? mapper.createObjectNode() : input);
        }
        if (blocks.size() == 0) {
            message.put("content", "");
        }
    }

    private ToolChatResult converseAnthropic(AiProvider provider, String apiKey, String system,
                                             List<Turn> turns, List<ToolSpec> tools,
                                             int maxTokens,
                                             java.util.function.Consumer<String> sink) {
        AiProtocol protocol = AiProtocol.ANTHROPIC;
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();
        if (model.isEmpty()) {
            return ToolChatResult.failure("error.ai.model.required", 0, false);
        }
        try {
            AiEndpointValidator.validateBaseUrl(provider.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return ToolChatResult.failure(e.getMessage(), 0, false);
        }
        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds());
        AtomicReference<String> stalledFor = new AtomicReference<>();
        try {
            HttpRequest request = buildConverseRequest(protocol, endpoint, model, apiKey,
                    timeout, system, turns, tools, maxTokens);
            HttpResponse<java.io.InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String detail = extractError(readAll(response.body()));
                boolean toolsRejected = response.statusCode() / 100 == 4 && !tools.isEmpty()
                        && detail.toLowerCase(java.util.Locale.ROOT)
                                .matches(".*(tool|function|does not support).*");
                log.warn("AI tool stream to '{}' failed: HTTP {} {}",
                        provider.getName(), response.statusCode(), detail);
                return ToolChatResult.failure("HTTP " + response.statusCode()
                        + (detail.isEmpty() ? "" : " — " + detail),
                        System.currentTimeMillis() - start, toolsRejected);
            }

            java.io.InputStream body = response.body();
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean firstLineSeen = new AtomicBoolean(false);
            long tickMs = Math.max(200L, Math.min(1000L, timeout.toMillis() / 4));
            ScheduledFuture<?> watchdog = STALL_WATCHDOG.scheduleAtFixedRate(() -> {
                long budget = firstLineSeen.get() ? timeout.toMillis() : FIRST_LINE_CEILING_MS;
                if (System.currentTimeMillis() - lastActivity.get() > budget
                        && stalledFor.compareAndSet(null,
                                "Stream stalled for over " + (budget / 1000) + "s")) {
                    try {
                        body.close();
                    } catch (IOException ignored) {
                        // Already closed.
                    }
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);

            StringBuilder text = new StringBuilder();
            String[] served = {""};
            Map<Integer, PartialCall> partials = new TreeMap<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    firstLineSeen.set(true);
                    lastActivity.set(System.currentTimeMillis());
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode root = parseOrNull(data);
                    if (root == null) {
                        continue;
                    }
                    String type = root.path("type").asString("");
                    if (served[0].isEmpty()) {
                        served[0] = servedModel(root);
                    }
                    if ("content_block_start".equals(type)
                            && "tool_use".equals(root.path("content_block").path("type")
                                    .asString(""))) {
                        PartialCall call = partials.computeIfAbsent(
                                root.path("index").asInt(0), i -> new PartialCall());
                        call.id = root.path("content_block").path("id").asString(call.id);
                        call.name = root.path("content_block").path("name").asString(call.name);
                        JsonNode input = root.path("content_block").path("input");
                        if (input.isObject() && input.size() > 0) {
                            call.args.append(input.toString());
                        }
                    } else if ("content_block_delta".equals(type)) {
                        String deltaType = root.path("delta").path("type").asString("");
                        if ("text_delta".equals(deltaType)) {
                            String piece = root.path("delta").path("text").asString("");
                            if (!piece.isEmpty()) {
                                text.append(piece);
                                sink.accept(piece);
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            PartialCall call = partials.computeIfAbsent(
                                    root.path("index").asInt(0), i -> new PartialCall());
                            call.args.append(
                                    root.path("delta").path("partial_json").asString(""));
                        }
                    } else if ("message_stop".equals(type)) {
                        break;
                    }
                }
            } finally {
                watchdog.cancel(false);
            }
            List<ToolCall> calls = new ArrayList<>();
            partials.forEach((index, call) -> calls.add(new ToolCall(
                    call.id.isEmpty() ? "toolu_" + index : call.id,
                    call.name, call.args.toString())));
            return ToolChatResult.success(text.toString(), calls, served[0],
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolChatResult.failure("Interrupted", System.currentTimeMillis() - start, false);
        } catch (IOException | RuntimeException e) {
            String stall = stalledFor.get();
            log.warn("AI tool stream to '{}' failed: {}", provider.getName(),
                    stall == null ? e.toString() : stall);
            return ToolChatResult.failure(stall == null
                    ? (e.getMessage() == null ? e.toString() : e.getMessage()) : stall,
                    System.currentTimeMillis() - start, false);
        }
    }

    /**
     * Ollama's native wire keeps OpenAI's message shape but wants tool arguments as a JSON
     * object, not the string OpenAI packs them into.
     */
    private void appendOllamaMessage(ArrayNode messages, Turn turn) {
        ObjectNode message = messages.addObject();
        if ("tool".equals(turn.role())) {
            message.put("role", "tool");
            message.put("content", turn.content() == null ? "" : turn.content());
            return;
        }
        message.put("role", turn.role());
        message.put("content", turn.content() == null ? "" : turn.content());
        if ("assistant".equals(turn.role()) && !turn.toolCalls().isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCall call : turn.toolCalls()) {
                ObjectNode c = calls.addObject();
                ObjectNode fn = c.putObject("function");
                fn.put("name", call.name());
                JsonNode args = parseOrNull(call.argumentsJson());
                fn.set("arguments", args == null ? mapper.createObjectNode() : args);
            }
        }
    }

    private ToolChatResult converseOllamaNative(AiProvider provider, String apiKey, String system,
                                                List<Turn> turns, List<ToolSpec> tools,
                                                int maxTokens,
                                                java.util.function.Consumer<String> sink) {
        AiProtocol protocol = AiProtocol.OLLAMA_NATIVE;
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();
        if (model.isEmpty()) {
            return ToolChatResult.failure("error.ai.model.required", 0, false);
        }
        try {
            AiEndpointValidator.validateBaseUrl(provider.getBaseUrl());
        } catch (IllegalArgumentException e) {
            return ToolChatResult.failure(e.getMessage(), 0, false);
        }
        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds());
        AtomicReference<String> stalledFor = new AtomicReference<>();
        try {
            HttpRequest request = buildConverseRequest(protocol, endpoint, model, apiKey,
                    timeout, system, turns, tools, maxTokens);
            HttpResponse<java.io.InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String detail = extractError(readAll(response.body()));
                boolean toolsRejected = response.statusCode() / 100 == 4 && !tools.isEmpty()
                        && detail.toLowerCase(java.util.Locale.ROOT)
                                .matches(".*(tool|function|does not support).*");
                log.warn("AI tool stream to '{}' failed: HTTP {} {}",
                        provider.getName(), response.statusCode(), detail);
                return ToolChatResult.failure("HTTP " + response.statusCode()
                        + (detail.isEmpty() ? "" : " — " + detail),
                        System.currentTimeMillis() - start, toolsRejected);
            }

            java.io.InputStream body = response.body();
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean firstLineSeen = new AtomicBoolean(false);
            long tickMs = Math.max(200L, Math.min(1000L, timeout.toMillis() / 4));
            ScheduledFuture<?> watchdog = STALL_WATCHDOG.scheduleAtFixedRate(() -> {
                long budget = firstLineSeen.get() ? timeout.toMillis() : FIRST_LINE_CEILING_MS;
                if (System.currentTimeMillis() - lastActivity.get() > budget
                        && stalledFor.compareAndSet(null,
                                "Stream stalled for over " + (budget / 1000) + "s")) {
                    try {
                        body.close();
                    } catch (IOException ignored) {
                        // Already closed.
                    }
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);

            StringBuilder text = new StringBuilder();
            String[] served = {""};
            List<ToolCall> calls = new ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    firstLineSeen.set(true);
                    lastActivity.set(System.currentTimeMillis());
                    JsonNode chunk = parseOrNull(line);
                    if (chunk == null) {
                        continue;
                    }
                    if (served[0].isEmpty()) {
                        served[0] = servedModel(chunk);
                    }
                    String piece = chunk.path("message").path("content").asString("");
                    if (!piece.isEmpty()) {
                        text.append(piece);
                        sink.accept(piece);
                    }
                    for (JsonNode call : chunk.path("message").path("tool_calls")) {
                        JsonNode fn = call.path("function");
                        // Native responses give an object; a proxy in between may hand back a
                        // string. Both must end up as the JSON text the registry parses.
                        String argsJson = fn.path("arguments").isObject()
                                ? fn.path("arguments").toString()
                                : fn.path("arguments").asString("");
                        calls.add(new ToolCall("call_" + calls.size(),
                                fn.path("name").asString(""), argsJson));
                    }
                    if (chunk.path("done").asBoolean(false)) {
                        break;
                    }
                }
            } finally {
                watchdog.cancel(false);
            }
            return ToolChatResult.success(text.toString(), calls, served[0],
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolChatResult.failure("Interrupted", System.currentTimeMillis() - start, false);
        } catch (IOException | RuntimeException e) {
            String stall = stalledFor.get();
            log.warn("AI tool stream to '{}' failed: {}", provider.getName(),
                    stall == null ? e.toString() : stall);
            return ToolChatResult.failure(stall == null
                    ? (e.getMessage() == null ? e.toString() : e.getMessage()) : stall,
                    System.currentTimeMillis() - start, false);
        }
    }

    /**
     * 把"深度思考"的意图翻译成请求体。
     *
     * <p>{@link AiReasoningEffort#DEFAULT} 直接返回:一个字段都不发,请求体与这个功能存在
     * 之前完全一样。这是硬要求,不是保守 —— 老供应商那一列是 null,不能被新功能改变行为。
     */
    private void applyReasoning(AiProtocol protocol, ObjectNode body, AiReasoningEffort effort,
                                int maxTokens) {
        if (effort == null || effort == AiReasoningEffort.DEFAULT) {
            return;
        }
        if (protocol == AiProtocol.ANTHROPIC) {
            Integer budget = effort.getThinkingBudget();
            if (budget == null) {
                // OFF:Anthropic 的默认就是不思考,省略字段就是关闭。
                return;
            }
            if (budget >= maxTokens) {
                // Anthropic 要求 budget_tokens 严格小于 max_tokens,否则整个请求 400。
                // 保存时已经校验过,这里拦住的是把 maxTokens 事后手工调小的情况:
                // 宁可少想一点,也不要让一次转换直接失败。
                log.warn("Thinking budget {} does not fit under max_tokens {}; sending the "
                        + "request without it", budget, maxTokens);
                return;
            }
            ObjectNode thinking = body.putObject("thinking");
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", budget);
            return;
        }
        if (protocol == AiProtocol.OLLAMA_NATIVE) {
            // 原生协议的思考开关是顶层布尔 think:"关"发 false,要思考发 true。
            // 刻意用布尔而不是 "low"/"high" 档位串 —— 档位串只有较新的 Ollama 认,
            // 布尔各版本通吃,DEFAULT 已在上面返回。
            body.put("think", effort != AiReasoningEffort.OFF);
            return;
        }
        // OpenAI 形状,包含 Ollama 的兼容端点。官方 OpenAI 的推理模型并非都认 "none"
        // (o 系列地板是 low;"none" 自 gpt-5.1 起才有,不认的值以 400 回绝),所以 OFF
        // 只对兼容端点承诺得死 —— 帮助文案 ai.reasoningHelp 里对用户写明了这一点。
        body.put("reasoning_effort", effort.getEffortLevel());
    }

    /** The provider's stored setting, with null (rows saved before the column existed) as DEFAULT. */
    private static AiReasoningEffort effortOf(AiProvider provider) {
        return provider.getReasoningEffort() == null
                ? AiReasoningEffort.DEFAULT : provider.getReasoningEffort();
    }

    /** Parses the response body once; null when it is blank or not JSON. */
    private JsonNode parseOrNull(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(responseBody);
        } catch (JacksonException e) {
            log.debug("Could not parse AI response body as JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Pulls the reply text out of whichever response shape the protocol uses. */
    private String extractText(AiProtocol protocol, JsonNode root) {
        if (root == null) {
            return "";
        }
        if (protocol == AiProtocol.OLLAMA_NATIVE) {
            // 原生回复直接是 message.content,没有 choices 这一层。
            return root.path("message").path("content").asString("");
        }
        if (protocol == AiProtocol.ANTHROPIC) {
            // content is a list of typed blocks; only the text ones carry the reply, and a
            // response may legitimately open with a non-text block.
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asString())) {
                    sb.append(block.path("text").asString(""));
                }
            }
            return sb.toString();
        }
        return root.path("choices").path(0).path("message").path("content").asString("");
    }

    /** The model the endpoint says answered, which gateways often remap from the alias sent. */
    private String servedModel(JsonNode root) {
        return root == null ? "" : root.path("model").asString("");
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
                if (node.isString() && !node.asString().isBlank()) {
                    return truncate(node.asString());
                }
                JsonNode nested = node.path("message");
                if (nested.isString() && !nested.asString().isBlank()) {
                    return truncate(nested.asString());
                }
            }
        } catch (JacksonException e) {
            // Not JSON: an HTML error page from a reverse proxy is itself the useful signal.
        }
        return truncate(responseBody);
    }

    private String truncate(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > MAX_ERROR_CHARS
                ? flat.substring(0, MAX_ERROR_CHARS) + "..." : flat;
    }

    /** One tool invocation the model asked for; arguments is the joined JSON fragment. */
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    /** A tool offered to the model; parameters is a JSON Schema object. */
    public record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    /**
     * One turn of a tool conversation, protocol-neutral.
     *
     * <p>role is one of user / assistant / tool. toolCallId is set only for role=tool.
     */
    public record Turn(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public static Turn user(String text) {
            return new Turn("user", text, List.of(), null);
        }

        public static Turn assistant(String text, List<ToolCall> calls) {
            return new Turn("assistant", text, calls == null ? List.of() : calls, null);
        }

        public static Turn toolResult(String toolCallId, String content) {
            return new Turn("tool", content, List.of(), toolCallId);
        }
    }

    /** Outcome of one streaming tool round: text seen so far plus the calls it asked for. */
    public record ToolChatResult(boolean success, String message, String text,
                                 List<ToolCall> toolCalls, String servedModel,
                                 long elapsedMs, boolean toolUnsupported) {

        static ToolChatResult success(String text, List<ToolCall> calls, String servedModel,
                                      long ms) {
            return new ToolChatResult(true, "OK", text, calls, servedModel, ms, false);
        }

        static ToolChatResult failure(String message, long ms, boolean toolUnsupported) {
            return new ToolChatResult(false, message, "", List.of(), "", ms, toolUnsupported);
        }
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
