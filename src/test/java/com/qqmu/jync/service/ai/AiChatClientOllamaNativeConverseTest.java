package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiChatClientOllamaNativeConverseTest {

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private String ndjsonBody = "";
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
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(ndjsonBody.getBytes(StandardCharsets.UTF_8));
        out.flush();
        exchange.close();
    }

    private AiProvider provider() {
        AiProvider p = new AiProvider();
        p.setName("ollama");
        p.setProtocol(AiProtocol.OLLAMA_NATIVE);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setModel("qwen2.5");
        p.setTimeoutSeconds(5);
        return p;
    }

    @Test
    void nativeStreamAccumulatesToolCallsAndStreamsContent() {
        ndjsonBody = """
                {"message":{"role":"assistant","content":"我先查"},"done":false}
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"project_status","arguments":{"projectId":7}}}]},"done":false}
                {"message":{"role":"assistant","content":""},"done":true}

                """;
        List<String> deltas = new ArrayList<>();

        AiChatClient.ToolChatResult result = client.converseStream(provider(), null, "SYS",
                List.of(AiChatClient.Turn.user("订单同步在跑吗")),
                List.of(new AiChatClient.ToolSpec("project_status", "status", Map.of(
                        "type", "object",
                        "properties", Map.of("projectId", Map.of("type", "integer"))))),
                512, deltas::add);

        assertThat(result.success()).isTrue();
        assertThat(deltas).containsExactly("我先查");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("project_status");
            assertThat(call.argumentsJson()).isEqualTo("{\"projectId\":7}");
        });

        JsonNode body = mapper.readTree(requestBodies.get(0));
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("options").path("num_predict").asInt()).isEqualTo(512);
        assertThat(body.path("tools").get(0).path("function").path("name").asString())
                .isEqualTo("project_status");
    }

    @Test
    void nativeToolResultIsSentBackAsToolRole() {
        ndjsonBody = "{\"message\":{\"content\":\"它在跑\"},\"done\":true}\n";
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("call_0", "project_status", "{\"projectId\":7}");

        client.converseStream(provider(), null, "SYS",
                List.of(AiChatClient.Turn.user("在跑吗"),
                        AiChatClient.Turn.assistant("我先查", List.of(call)),
                        AiChatClient.Turn.toolResult("call_0", "{\"running\":true}")),
                List.of(), 512, text -> { });

        JsonNode messages = mapper.readTree(requestBodies.get(0)).path("messages");
        assertThat(messages.get(2).path("role").asString()).isEqualTo("assistant");
        assertThat(messages.get(2).path("tool_calls").get(0).path("function").path("name")
                .asString()).isEqualTo("project_status");
        assertThat(messages.get(3).path("role").asString()).isEqualTo("tool");
    }
}
