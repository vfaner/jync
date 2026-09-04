package com.synctool.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;

/**
 * Probes a real local HTTP server rather than a mocked client.
 *
 * <p>What can break here is the wire format -- which header carries the key, which path the
 * request lands on -- and only an actual request exercises that.
 */
class AiConnectionTestServiceTest {

    private HttpServer server;
    private AiConnectionTestService service;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Requests the server received, in order. */
    private final List<Recorded> received = new ArrayList<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody =
            new AtomicReference<>("{\"model\":\"some-model\"}");

    /** Keeps the concrete {@code Headers} type so lookups stay case-insensitive. */
    private record Recorded(String path, Headers headers, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        service = new AiConnectionTestService(new AiChatClient());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        received.add(new Recorded(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(), body));

        byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AiProvider provider(AiProtocol protocol, String base) {
        AiProvider p = new AiProvider();
        p.setName("local");
        p.setProtocol(protocol);
        p.setBaseUrl(base);
        p.setModel("some-model");
        p.setTimeoutSeconds(5);
        return p;
    }

    // --- wire format --------------------------------------------------------------------

    @Test
    void openAiSendsABearerTokenToTheChatCompletionsPath() throws Exception {
        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.OPENAI, baseUrl() + "/v1"), "sk-abc");

        assertThat(result.isSuccess()).isTrue();
        Recorded r = received.get(0);
        assertThat(r.path()).isEqualTo("/v1/chat/completions");
        assertThat(r.headers().getFirst("Authorization")).isEqualTo("Bearer sk-abc");

        JsonNode body = mapper.readTree(r.body());
        assertThat(body.path("model").asText()).isEqualTo("some-model");
        // One token is enough to prove the key and model work, and costs almost nothing.
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void anthropicSendsTheApiKeyHeaderAndAVersion() {
        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.ANTHROPIC, baseUrl()), "sk-ant");

        assertThat(result.isSuccess()).isTrue();
        Recorded r = received.get(0);
        assertThat(r.path()).isEqualTo("/v1/messages");
        assertThat(r.headers().getFirst("x-api-key")).isEqualTo("sk-ant");
        assertThat(r.headers().getFirst("anthropic-version")).isNotBlank();
        // The bearer form would be ignored by Anthropic, so it must not be the only auth sent.
        assertThat(r.headers().getFirst("Authorization")).isNull();
    }

    // --- base URL resolution -------------------------------------------------------------

    @Test
    void aTrailingSlashDoesNotProduceADoubledPath() {
        service.test(provider(AiProtocol.OPENAI, baseUrl() + "/v1/"), "k");
        assertThat(received.get(0).path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void aBaseUrlThatAlreadyNamesTheEndpointIsNotSuffixedTwice() {
        // Users paste the full endpoint from vendor docs; appending again would 404.
        service.test(provider(AiProtocol.OPENAI, baseUrl() + "/v1/chat/completions"), "k");
        assertThat(received.get(0).path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void theResolvedEndpointIsReportedBackForDebugging() {
        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.OPENAI, baseUrl() + "/v1"), "k");

        // When a key is fine but the path is off by a segment, this is the only useful clue.
        assertThat(result.getEndpoint()).isEqualTo(baseUrl() + "/v1/chat/completions");
    }

    // --- failures -----------------------------------------------------------------------

    @Test
    void anAuthFailureReportsTheEndpointsOwnMessage() {
        status.set(401);
        responseBody.set("{\"error\":{\"message\":\"Invalid API key provided\"}}");

        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.OPENAI, baseUrl()), "wrong");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("401").contains("Invalid API key provided");
    }

    @Test
    void aNonJsonErrorPageIsStillSurfaced() {
        // A reverse proxy in front of an intranet endpoint returns HTML, not JSON.
        status.set(502);
        responseBody.set("<html><body>Bad Gateway</body></html>");

        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.OPENAI, baseUrl()), "k");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("502").contains("Bad Gateway");
    }

    @Test
    void anUnreachableHostFailsWithoutThrowing() {
        AiProvider p = provider(AiProtocol.OPENAI, "http://127.0.0.1:1");
        p.setTimeoutSeconds(2);

        AiConnectionTestService.TestResult result = service.test(p, "k");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotBlank();
    }

    @Test
    void aMissingModelIsRejectedBeforeAnyRequestIsSent() {
        AiProvider p = provider(AiProtocol.OPENAI, baseUrl());
        p.setModel("  ");

        AiConnectionTestService.TestResult result = service.test(p, "k");

        assertThat(result.isSuccess()).isFalse();
        assertThat(received).isEmpty();
    }

    // --- the key must not leak ----------------------------------------------------------

    @Test
    void theApiKeyNeverAppearsInTheResult() {
        status.set(401);
        responseBody.set("{\"error\":{\"message\":\"bad key\"}}");

        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.OPENAI, baseUrl()), "sk-super-secret");

        // The result is rendered in the browser and stored in the metadata DB as a badge
        // tooltip, so a key echoed into it would outlive the request.
        assertThat(result.getMessage()).doesNotContain("sk-super-secret");
        assertThat(result.getEndpoint()).doesNotContain("sk-super-secret");
    }

    @Test
    void aSuccessNamesTheModelThatActuallyAnswered() {
        // Gateways remap aliases; knowing which model replied matters when judging output.
        responseBody.set("{\"model\":\"some-model-0711\"}");

        AiConnectionTestService.TestResult result =
                service.test(provider(AiProtocol.OPENAI, baseUrl()), "k");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("some-model-0711");
    }
}
