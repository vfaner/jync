package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AiReasoningEffort;

/**
 * 推理力度(a.k.a. 深度思考)怎么落到请求体上。
 *
 * <p>背景:本机 Ollama + qwen3:8b 实测,思考 token 计入输出预算 —— {@code max_tokens:64}
 * 时正文为空、思考 517 字符全在 {@code message.reasoning} 里,界面上表现为"一直转圈然后
 * 报模型返回了空内容"。Ollama 的兼容端点认 {@code reasoning_effort:"none"} 且确实关闭思考
 * (实测 4 tokens、0 思考、正文正常)。所以这是个真开关,不是省时间的摆设。
 *
 * <p>三条设计约束,每条都有对应用例:
 * <ul>
 *   <li>"跟随默认"必须一个字段都不发 —— 现有用户的端点行为一字不变;</li>
 *   <li>Anthropic 用 thinking 预算而不是 reasoning_effort,且预算必须小于 max_tokens;</li>
 *   <li>探测永不携带它。探测是 {@code max_tokens=1} 的最小请求,Anthropic 会因
 *       {@code max_tokens <= budget_tokens} 直接 400,而"保存前先探测"是配置流程的命门。</li>
 * </ul>
 */
class AiChatClientReasoningTest {

    private static final String OPENAI_REPLY =
            "{\"model\":\"m\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
    private static final String ANTHROPIC_REPLY =
            "{\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
    private static final String OPENAI_SSE =
            "data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: [DONE]\n\n";

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final List<String> bodies = new ArrayList<>();
    private String reply = OPENAI_REPLY;
    private boolean sse;

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
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        lastBody.set(body);
        bodies.add(body);

        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                sse ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, sse ? 0 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private AiProvider provider(AiProtocol protocol, AiReasoningEffort effort, int maxTokens) {
        AiProvider p = new AiProvider();
        p.setName("reasoning-test");
        p.setProtocol(protocol);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()
                + (protocol == AiProtocol.ANTHROPIC ? "" : "/v1"));
        p.setModel("m");
        p.setTimeoutSeconds(5);
        p.setMaxTokens(maxTokens);
        p.setReasoningEffort(effort);
        return p;
    }

    /** Sends one completion and returns the request body that actually went out. */
    private JsonNode complete(AiProtocol protocol, AiReasoningEffort effort, int maxTokens) {
        AiChatClient.ChatResult result =
                client.complete(provider(protocol, effort, maxTokens), "k", null, "ping", maxTokens);
        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        try {
            return mapper.readTree(lastBody.get());
        } catch (IOException e) {
            throw new AssertionError("请求体不是 JSON: " + lastBody.get(), e);
        }
    }

    // --- 跟随默认一根毛都不发 -----------------------------------------------------------

    @Test
    void theDefaultEffortAddsNothingToAnOpenAiShapedRequest() {
        JsonNode body = complete(AiProtocol.OPENAI, null, 4096);
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.has("thinking")).isFalse();
    }

    @Test
    void theDefaultEffortAddsNothingToAnAnthropicRequest() {
        reply = ANTHROPIC_REPLY;
        JsonNode body = complete(AiProtocol.ANTHROPIC, AiReasoningEffort.DEFAULT, 4096);
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.has("thinking")).isFalse();
    }

    // --- OpenAI 形状(含 Ollama) ---------------------------------------------------------

    @Test
    void turningReasoningOffSendsTheNoneLevel() {
        JsonNode body = complete(AiProtocol.OPENAI, AiReasoningEffort.OFF, 4096);
        assertThat(body.path("reasoning_effort").asText())
                .as("Ollama/vLLM 这类兼容端点认 none;官方 OpenAI 不认,所以默认绝不会发它")
                .isEqualTo("none");
    }

    @Test
    void ollamaTakesExactlyTheSameSwitch() {
        JsonNode body = complete(AiProtocol.OLLAMA, AiReasoningEffort.OFF, 4096);
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
    }

    @Test
    void anExplicitLevelIsPassedThroughVerbatim() {
        assertThat(complete(AiProtocol.OPENAI, AiReasoningEffort.LOW, 4096)
                .path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(complete(AiProtocol.OPENAI, AiReasoningEffort.HIGH, 4096)
                .path("reasoning_effort").asText()).isEqualTo("high");
    }

    // --- Anthropic:预算而不是档位 --------------------------------------------------------

    @Test
    void anAnthropicEndpointGetsAThinkingBudgetNotAnEffortLevel() {
        reply = ANTHROPIC_REPLY;
        JsonNode body = complete(AiProtocol.ANTHROPIC, AiReasoningEffort.HIGH, 16384);

        assertThat(body.has("reasoning_effort"))
                .as("Anthropic 不认 reasoning_effort,发过去就是 400")
                .isFalse();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        int budget = body.path("thinking").path("budget_tokens").asInt(-1);
        assertThat(budget)
                .as("Anthropic 要求 budget_tokens >= 1024")
                .isGreaterThanOrEqualTo(1024);
        assertThat(body.path("max_tokens").asInt())
                .as("Anthropic 要求 max_tokens 严格大于思考预算,否则整个请求 400")
                .isGreaterThan(budget);
    }

    /**
     * 兜底:预算塞不进 max_tokens 时宁可不思考,也不让这次转换直接 400。
     *
     * <p>正常路径下走不到这里 —— 保存时会拦下这种组合(见 {@code AiProviderService}),
     * 这条挡的是把 maxTokens 事后手工改小的情况。
     */
    @Test
    void aBudgetThatDoesNotFitUnderMaxTokensIsLeftOutRatherThanFailingTheRequest() {
        reply = ANTHROPIC_REPLY;
        JsonNode body = complete(AiProtocol.ANTHROPIC, AiReasoningEffort.HIGH, 4096);

        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.has("thinking"))
                .as("8192 的预算塞不进 4096,发了就是 400")
                .isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void aHigherLevelMeansABiggerAnthropicBudget() {
        reply = ANTHROPIC_REPLY;
        int low = complete(AiProtocol.ANTHROPIC, AiReasoningEffort.LOW, 16384)
                .path("thinking").path("budget_tokens").asInt();
        int high = complete(AiProtocol.ANTHROPIC, AiReasoningEffort.HIGH, 16384)
                .path("thinking").path("budget_tokens").asInt();
        assertThat(high).as("高档要真的比低档想得久,否则这个下拉没意义").isGreaterThan(low);
    }

    @Test
    void turningReasoningOffOnAnthropicOmitsThinkingAltogether() {
        reply = ANTHROPIC_REPLY;
        JsonNode body = complete(AiProtocol.ANTHROPIC, AiReasoningEffort.OFF, 4096);
        assertThat(body.has("thinking"))
                .as("Anthropic 的默认就是不思考,省略字段即为关闭")
                .isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    // --- 草稿走的是流式,得跟着一起带 ------------------------------------------------------

    @Test
    void theStreamingPathCarriesTheSettingToo() {
        sse = true;
        reply = OPENAI_SSE;

        List<String> deltas = new ArrayList<>();
        AiChatClient.ChatResult result = client.stream(
                provider(AiProtocol.OLLAMA, AiReasoningEffort.OFF, 4096), "k", null, "ping", 4096,
                deltas::add);

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        try {
            JsonNode body = mapper.readTree(lastBody.get());
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("reasoning_effort").asText())
                    .as("草稿是流式出的;设置只挂在 complete 上就等于没做")
                    .isEqualTo("none");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // --- 最要紧的一条 -------------------------------------------------------------------

    @Test
    void theProbeNeverCarriesReasoningSettings() {
        AiProvider p = provider(AiProtocol.OPENAI, AiReasoningEffort.HIGH, 4096);

        AiChatClient.ChatResult result = client.probe(p, "k");

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        try {
            JsonNode body = mapper.readTree(lastBody.get());
            assertThat(body.path("max_tokens").asInt()).isEqualTo(1);
            assertThat(body.has("reasoning_effort"))
                    .as("max_tokens=1 再带推理参数,探测会以端点自己的 400 收场")
                    .isFalse();
            assertThat(body.has("thinking")).isFalse();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theProbeNeverCarriesAThinkingBudgetEither() {
        sse = false;
        reply = ANTHROPIC_REPLY;
        AiProvider p = provider(AiProtocol.ANTHROPIC, AiReasoningEffort.HIGH, 16384);

        AiChatClient.ChatResult result = client.probe(p, "k");

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        try {
            JsonNode body = mapper.readTree(lastBody.get());
            assertThat(body.path("max_tokens").asInt())
                    .as("探测永远是 1,而 Anthropic 的预算是按 max_tokens 比例算的")
                    .isEqualTo(1);
            assertThat(body.has("thinking"))
                    .as("哪怕 provider 存档里开着高档思考,探测也必须裸奔")
                    .isFalse();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
