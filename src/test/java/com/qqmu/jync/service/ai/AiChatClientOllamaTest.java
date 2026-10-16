package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;

/**
 * Ollama 必须是走 OpenAI 那条线上路径的"本地预设",不是第三种协议。
 *
 * <p>这条断言的价值在于它是行为级的:{@code AiChatClient} 里所有分支判断都是
 * {@code protocol == ANTHROPIC} 与 else,所以加一个枚举成员本不需要改逻辑。
 * 将来谁要是加了 {@code else if (protocol == OLLAMA)},这里立刻变红 ——
 * 而那正是把 Ollama 引向它原生 /api/chat 的第一步(那条路的流式是 NDJSON,
 * 会被解析器当成"网关缓冲了整段",流式静默失效)。
 */
class AiChatClientOllamaTest {

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastAnthropicKey = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

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
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastAnthropicKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        // 真实的 Ollama 兼容端点回答的就是这个形状(本机 0.33.2 实测)。
        byte[] out = ("{\"model\":\"qwen3:8b\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"SELECT 1\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Test
    void anOllamaProviderTalksTheOpenAiWireProtocol() {
        AiProvider p = new AiProvider();
        p.setName("local-ollama");
        p.setProtocol(AiProtocol.OLLAMA);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        p.setModel("qwen3:8b");
        p.setTimeoutSeconds(5);

        // 本地端点通常没有密钥,生产里传进来的就是 null。
        AiChatClient.ChatResult result = client.complete(p, null, "SYS", "USER", 512);

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(result.getText()).isEqualTo("SELECT 1");
        assertThat(lastPath.get())
                .as("拼出来的路径必须落在兼容端点上")
                .isEqualTo("/v1/chat/completions");
        // 头值的尾随空格在传输中被规范化掉了,所以这里只认协议名。
        assertThat(lastAuth.get())
                .as("空密钥也要走 Bearer")
                .startsWith("Bearer");
        assertThat(lastAnthropicKey.get())
                .as("不该带上 Anthropic 的 x-api-key")
                .isNull();
        assertThat(lastBody.get())
                .as("system 提示要作为第一条 message,和 OpenAI 形状一致")
                .contains("\"role\":\"system\"", "\"role\":\"user\"");
    }
}
