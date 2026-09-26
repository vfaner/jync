package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;

/**
 * Wire-format tests for {@link AiChatClient#stream}, against a real local HTTP server.
 *
 * <p>The two protocols chunk differently — OpenAI ends with {@code data: [DONE]}, Anthropic with
 * an {@code event: message_stop} — and the reply text sits in different places in each chunk.
 * A mocked client would only prove the mock was shaped like the author's assumption, so this
 * serves actual SSE bytes and asserts what comes back.
 *
 * <p>The timeout case matters as much as the happy path: {@code HttpRequest.timeout} only bounds
 * the header wait, so a provider that drip-feeds chunks forever would otherwise hang the draft
 * worker well past the configured timeout. The deadline is enforced per line inside
 * {@code stream()} and the drip test below is what pins that behaviour.
 */
class AiChatClientStreamTest {

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Full SSE body the server will send. */
    private String sseBody = "";
    /** Non-2xx path: status and error body. */
    private int status = 200;
    private String errorBody = "";
    /** Drip control: bytes per write and pause between writes; 0 sends the body in one go. */
    private int dripBytes = 0;
    private long dripDelayMs = 0;
    /** Pause after the headers, before the first body byte: a gateway buffering a generation. */
    private long initialDelayMs = 0;

    private final List<String> requestBodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            requestBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        if (status / 100 != 2) {
            byte[] out = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        // Length 0 with HTTP/1.1 means chunked: each flush reaches the client immediately.
        exchange.sendResponseHeaders(200, 0);
        try {
            if (initialDelayMs > 0) {
                Thread.sleep(initialDelayMs);
            }
            OutputStream out = exchange.getResponseBody();
            byte[] all = sseBody.getBytes(StandardCharsets.UTF_8);
            int step = dripBytes > 0 ? dripBytes : all.length;
            for (int off = 0; off < all.length; off += step) {
                out.write(all, off, Math.min(step, all.length - off));
                out.flush();
                if (dripDelayMs > 0 && off + step < all.length) {
                    Thread.sleep(dripDelayMs);
                }
            }
        } catch (IOException | InterruptedException e) {
            // The timeout test abandons the response mid-stream; a broken pipe here is expected.
        } finally {
            exchange.close();
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AiProvider provider(AiProtocol protocol) {
        AiProvider p = new AiProvider();
        p.setName("local");
        p.setProtocol(protocol);
        p.setBaseUrl(baseUrl());
        p.setModel("some-model");
        p.setTimeoutSeconds(5);
        return p;
    }

    // --- OpenAI --------------------------------------------------------------------------

    @Test
    void openAiDeltasReachTheSinkAndAccumulateIntoTheResult() throws Exception {
        sseBody = "data: {\"model\":\"gpt-x\",\"choices\":[{\"delta\":{\"content\":\"SELECT\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\" 1\"}}]}\n\n"
                + "data: [DONE]\n\n";

        List<String> deltas = new ArrayList<>();
        AiChatClient.ChatResult result = client.stream(provider(AiProtocol.OPENAI), "sk-1",
                "SYS", "USER", 1024, deltas::add);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("SELECT 1");
        assertThat(result.effectiveModel()).isEqualTo("gpt-x");
        // The whole point of streaming: the reviewer sees text before the reply is complete.
        assertThat(deltas).hasSize(2);

        JsonNode body = mapper.readTree(requestBodies.get(0));
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    }

    @Test
    void aCompatibleEndpointThatSendsWholeMessageChunksIsStillRead() throws Exception {
        // Not every OpenAI-compatible gateway emits delta objects; some send message chunks.
        sseBody = "data: {\"choices\":[{\"message\":{\"content\":\"ALL\"}}]}\n\n"
                + "data: [DONE]\n\n";

        AiChatClient.ChatResult result = client.stream(provider(AiProtocol.OPENAI), "k",
                null, "USER", 64, t -> { });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("ALL");
    }

    // --- Anthropic ------------------------------------------------------------------------

    @Test
    void anthropicTextDeltasAreReadUntilMessageStop() {
        sseBody = "event: message_start\n"
                + "data: {\"type\":\"message_start\"}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"CREATE \"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"VIEW v\"}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";

        List<String> deltas = new ArrayList<>();
        AiChatClient.ChatResult result = client.stream(provider(AiProtocol.ANTHROPIC), "sk-ant",
                "SYS", "USER", 1024, deltas::add);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("CREATE VIEW v");
        assertThat(deltas).containsExactly("CREATE ", "VIEW v");
        // Anthropic names the model inside message_start's nested payload, not per chunk; the
        // fallback to the requested model keeps the UI honest rather than blank.
        assertThat(result.effectiveModel()).isEqualTo("some-model");
    }

    @Test
    void aGatewayThatIgnoresStreamTrueFallsBackToTheBufferedReply() {
        // Detection is by body shape, not Content-Type: such gateways often still claim
        // text/event-stream while sending one plain completion object.
        sseBody = "{\"model\":\"gw\",\"choices\":[{\"message\":{\"content\":\"BUFFERED\"}}]}";

        List<String> deltas = new ArrayList<>();
        AiChatClient.ChatResult result = client.stream(provider(AiProtocol.OPENAI), "k",
                null, "USER", 64, deltas::add);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("BUFFERED");
        // One call with the whole text: the page still fills the editor, just not gradually.
        assertThat(deltas).containsExactly("BUFFERED");
    }

    @Test
    void chunkShapeIsDetectedEvenWhenItDisagreesWithTheConfiguredProtocol() {
        // Gateways are routinely filed under one protocol and answer with the other's events;
        // assuming the configured shape would silently drop every delta.
        sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"CROSS\"}}]}\n\n"
                + "data: [DONE]\n\n";

        AiChatClient.ChatResult result = client.stream(provider(AiProtocol.ANTHROPIC), "k",
                null, "USER", 64, t -> { });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("CROSS");
    }

    // --- failures -------------------------------------------------------------------------

    @Test
    void aNon2xxStreamResponseReportsTheEndpointsOwnError() {
        status = 429;
        errorBody = "{\"error\":{\"message\":\"Quota exceeded\"}}";

        AiChatClient.ChatResult result = client.stream(provider(AiProtocol.OPENAI), "k",
                null, "USER", 64, t -> { });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("429").contains("Quota exceeded");
    }

    @Test
    void aStreamThatGoesQuietMidFlightIsCutOff() {
        // Chunks 1.5s apart against a 1s stall limit: the stream is dead, not slow. Without
        // the stall clock this call would block until the server finished dripping.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            body.append("data: {\"choices\":[{\"delta\":{\"content\":\"chunk-")
                    .append(i).append("\"}}]}\n\n");
        }
        body.append("data: [DONE]\n\n");
        sseBody = body.toString();
        dripBytes = 200;
        dripDelayMs = 1500;

        AiProvider p = provider(AiProtocol.OPENAI);
        p.setTimeoutSeconds(1);

        AiChatClient.ChatResult result = client.stream(p, "k", null, "USER", 64, t -> { });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("stalled");
    }

    @Test
    void aLongSilenceBeforeTheFirstByteIsNotAStall() {
        // The shape of the user's real complaint: a gateway that answers the headers at once
        // and then goes quiet for the whole generation. Cutting that silence would fail a
        // draft the buffered call completes, so the stall clock starts at the first body byte.
        sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"WORTH THE WAIT\"}}]}\n\n"
                + "data: [DONE]\n\n";
        initialDelayMs = 1500;

        AiProvider p = provider(AiProtocol.OPENAI);
        p.setTimeoutSeconds(1);

        AiChatClient.ChatResult result = client.stream(p, "k", null, "USER", 64, t -> { });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("WORTH THE WAIT");
    }

    @Test
    void aMissingModelIsRejectedBeforeAnyRequestIsSent() {
        AiProvider p = provider(AiProtocol.OPENAI);
        p.setModel("  ");

        AiChatClient.ChatResult result = client.stream(p, "k", null, "USER", 64, t -> { });

        assertThat(result.isSuccess()).isFalse();
        assertThat(requestBodies).isEmpty();
    }
}
