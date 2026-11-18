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

class AiChatClientConverseTest {

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private String sseBody = "";
    private int status = 200;
    private String errorBody = "";
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
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(sseBody.getBytes(StandardCharsets.UTF_8));
        out.flush();
        exchange.close();
    }

    private AiProvider provider(AiProtocol protocol) {
        AiProvider p = new AiProvider();
        p.setName("local");
        p.setProtocol(protocol);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setModel("some-model");
        p.setTimeoutSeconds(5);
        return p;
    }

    private static final AiChatClient.ToolSpec PROJECT_STATUS = new AiChatClient.ToolSpec(
            "project_status", "Read one project's runtime status", Map.of(
                    "type", "object",
                    "properties", Map.of("projectId", Map.of("type", "integer")),
                    "required", List.of("projectId")));

    @Test
    void openAiStreamsTextAndJoinsFragmentedToolArguments() {
        sseBody = """
                data: {"model":"gpt-x","choices":[{"delta":{"content":"我先查一下"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"project_status","arguments":"{\\"projectId\\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":7}"}}]}}]}

                data: [DONE]

                """;
        List<String> deltas = new ArrayList<>();

        AiChatClient.ToolChatResult result = client.converseStream(
                provider(AiProtocol.OPENAI), "sk-1", "SYS",
                List.of(AiChatClient.Turn.user("订单同步在跑吗")),
                List.of(PROJECT_STATUS), 512, deltas::add);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("我先查一下");
        assertThat(deltas).containsExactly("我先查一下");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_1");
            assertThat(call.name()).isEqualTo("project_status");
            // 参数被拆成两片，拼接后必须是完整 JSON。
            assertThat(call.argumentsJson()).isEqualTo("{\"projectId\":7}");
        });

        JsonNode body = mapper.readTree(requestBodies.get(0));
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("tools").get(0).path("type").asString()).isEqualTo("function");
        assertThat(body.path("tools").get(0).path("function").path("name").asString())
                .isEqualTo("project_status");
    }

    @Test
    void openAiSendsToolResultsAsToolRoleMessages() {
        sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"它在跑\"}}]}\n\ndata: [DONE]\n\n";
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("call_1", "project_status", "{\"projectId\":7}");

        client.converseStream(provider(AiProtocol.OPENAI), "sk-1", "SYS",
                List.of(AiChatClient.Turn.user("订单同步在跑吗"),
                        AiChatClient.Turn.assistant("我先查一下", List.of(call)),
                        AiChatClient.Turn.toolResult("call_1", "{\"running\":true}")),
                List.of(PROJECT_STATUS), 512, text -> { });

        JsonNode messages = mapper.readTree(requestBodies.get(0)).path("messages");
        assertThat(messages.get(0).path("role").asString()).isEqualTo("system");
        assertThat(messages.get(2).path("role").asString()).isEqualTo("assistant");
        assertThat(messages.get(2).path("tool_calls").get(0).path("id").asString())
                .isEqualTo("call_1");
        assertThat(messages.get(3).path("role").asString()).isEqualTo("tool");
        assertThat(messages.get(3).path("tool_call_id").asString()).isEqualTo("call_1");
        assertThat(messages.get(3).path("content").asString()).isEqualTo("{\"running\":true}");
    }

    @Test
    void openAiBufferedJsonBodyIsStillDelivered() {
        // 网关无视 stream:true，返回一整段普通完成体。
        sseBody = "{\"model\":\"gpt-x\",\"choices\":[{\"message\":{\"content\":\"整段回答\"}}]}";
        List<String> deltas = new ArrayList<>();

        AiChatClient.ToolChatResult result = client.converseStream(
                provider(AiProtocol.OPENAI), "sk-1", "SYS",
                List.of(AiChatClient.Turn.user("hi")), List.of(), 128, deltas::add);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("整段回答");
        assertThat(deltas).containsExactly("整段回答");
    }

    @Test
    void aToolUnsupported4xxIsFlagged() {
        status = 400;
        errorBody = "{\"error\":{\"message\":\"tools are not supported by this model\"}}";

        AiChatClient.ToolChatResult result = client.converseStream(
                provider(AiProtocol.OPENAI), "sk-1", "SYS",
                List.of(AiChatClient.Turn.user("hi")), List.of(PROJECT_STATUS), 128, t -> { });

        assertThat(result.success()).isFalse();
        assertThat(result.toolUnsupported()).isTrue();
    }

    @Test
    void aPlainProviderErrorIsNotMistakenForToolUnsupported() {
        status = 401;
        errorBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        AiChatClient.ToolChatResult result = client.converseStream(
                provider(AiProtocol.OPENAI), "sk-1", "SYS",
                List.of(AiChatClient.Turn.user("hi")), List.of(PROJECT_STATUS), 128, t -> { });

        assertThat(result.success()).isFalse();
        assertThat(result.toolUnsupported()).isFalse();
    }
}
