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

class AiChatClientAnthropicConverseTest {

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private String sseBody = "";
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
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(sseBody.getBytes(StandardCharsets.UTF_8));
        out.flush();
        exchange.close();
    }

    private AiProvider provider() {
        AiProvider p = new AiProvider();
        p.setName("claude");
        p.setProtocol(AiProtocol.ANTHROPIC);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setModel("claude-x");
        p.setTimeoutSeconds(5);
        return p;
    }

    @Test
    void anthropicStreamsTextDeltasAndJoinsInputJsonDeltas() {
        sseBody = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"我先查"}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"project_status","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"projectId\\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":":7}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<String> deltas = new ArrayList<>();

        AiChatClient.ToolChatResult result = client.converseStream(provider(), "sk-ant", "SYS",
                List.of(AiChatClient.Turn.user("订单同步在跑吗")),
                List.of(new AiChatClient.ToolSpec("project_status", "status", Map.of(
                        "type", "object",
                        "properties", Map.of("projectId", Map.of("type", "integer"))))),
                512, deltas::add);

        assertThat(result.success()).isTrue();
        assertThat(deltas).containsExactly("我先查");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_1");
            assertThat(call.name()).isEqualTo("project_status");
            assertThat(call.argumentsJson()).isEqualTo("{\"projectId\":7}");
        });

        JsonNode body = mapper.readTree(requestBodies.get(0));
        assertThat(body.path("system").asString()).isEqualTo("SYS");
        assertThat(body.path("tools").get(0).path("input_schema").path("type").asString())
                .isEqualTo("object");
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
    }

    @Test
    void anthropicSendsToolResultsAsUserBlocks() {
        sseBody = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"它在跑"}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("toolu_1", "project_status", "{\"projectId\":7}");

        client.converseStream(provider(), "sk-ant", "SYS",
                List.of(AiChatClient.Turn.user("在跑吗"),
                        AiChatClient.Turn.assistant("我先查", List.of(call)),
                        AiChatClient.Turn.toolResult("toolu_1", "{\"running\":true}")),
                List.of(), 512, text -> { });

        JsonNode messages = mapper.readTree(requestBodies.get(0)).path("messages");
        assertThat(messages.get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(messages.get(1).path("content").get(1).path("type").asString())
                .isEqualTo("tool_use");
        assertThat(messages.get(2).path("role").asString()).isEqualTo("user");
        assertThat(messages.get(2).path("content").get(0).path("type").asString())
                .isEqualTo("tool_result");
        assertThat(messages.get(2).path("content").get(0).path("tool_use_id").asString())
                .isEqualTo("toolu_1");
    }
}
