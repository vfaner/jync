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
 * Ollama 的原生协议 —— {@code /api/chat},不是它的 OpenAI 兼容端点。
 *
 * <p>存在的理由:{@link AiProtocol#OLLAMA} 是兼容预设(预填 {@code /v1}),而原生端点在有
 * 些部署里是唯一开着的 —— 代理只放行了 {@code /api/*},或者兼容层版本太老。数据形状与 OpenAI
 * 形状有三处不同,每一处都对应下面的用例:请求体是 {@code options.num_predict} 而不是
 * {@code max_tokens};响应正文在 {@code message.content};流式是一行一个 JSON 对象(NDJSON),
 * 没有 {@code data:} 前缀。
 *
 * <p>最后这一点曾经是这个协议做不了的原因:裸行会被流解析器判成"网关把整段回复缓冲了",
 * 收进 {@code rawBody},多行 JSON 拼起来解析失败,回复整个变空。所以本类的核心用例是
 * {@link #aNativeStreamArrivesOneDeltaAtATime} —— 它要求增量<b>逐个</b>到达 sink,
 * 攒完再一次性交出来就不算流式。
 *
 * <p>新增枚举成员而不是改 {@code OLLAMA} 的语义,是为了让已存档的行(baseUrl 定死
 * {@code /v1})继续拼出 {@code /v1/chat/completions};{@code AiChatClientOllamaTest}
 * 继续钉着那一条。
 */
class AiChatClientOllamaNativeTest {

    private static final String NATIVE_REPLY =
            "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"done\":true,\"done_reason\":\"stop\"}";

    /** 真实的 NDJSON:一行一个对象,空行与畸形行都会出现,最后一行 done:true 收尾。 */
    private static final String NATIVE_NDJSON =
            "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"SE\"},\"done\":false}\n"
                    + "\n"
                    + "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"LE\"},\"done\":false}\n"
                    + "not json at all\n"
                    + "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"CT\"},\"done\":false}\n"
                    + "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}\n";

    private HttpServer server;
    private final AiChatClient client = new AiChatClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAccept = new AtomicReference<>();
    private String reply = NATIVE_REPLY;
    private int status = 200;

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
        lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        // 原生端点两种模式都回 application/json —— 流式也不是 SSE。
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private AiProvider provider(AiReasoningEffort effort, int maxTokens) {
        AiProvider p = new AiProvider();
        p.setName("native-ollama");
        p.setProtocol(AiProtocol.OLLAMA_NATIVE);
        // 原生端口的 baseUrl 不带 /v1 —— 那一段是兼容层的东西。
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setModel("qwen3:8b");
        p.setTimeoutSeconds(5);
        p.setMaxTokens(maxTokens);
        p.setReasoningEffort(effort);
        return p;
    }

    private JsonNode completeBody(AiReasoningEffort effort, int maxTokens) {
        AiChatClient.ChatResult result =
                client.complete(provider(effort, maxTokens), null, "SYS", "USER", maxTokens);
        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        try {
            return mapper.readTree(lastBody.get());
        } catch (IOException e) {
            throw new AssertionError("请求体不是 JSON: " + lastBody.get(), e);
        }
    }

    // --- 端点与响应形状 -----------------------------------------------------------------

    @Test
    void theNativeEndpointIsChatNotTheCompatibilityPath() {
        AiChatClient.ChatResult result =
                client.complete(provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512);

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(lastPath.get())
                .as("原生协议打的是 /api/chat;带上 /v1 就是 404")
                .isEqualTo("/api/chat");
    }

    @Test
    void aNativeReplyIsReadFromMessageContent() {
        AiChatClient.ChatResult result =
                client.complete(provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512);

        assertThat(result.getText())
                .as("原生正文在 message.content;OpenAI 的 choices[0].message.content 在这里不存在")
                .isEqualTo("ok");
        assertThat(result.effectiveModel())
                .as("原生响应顶层也有 model,跟兼容端点读的是同一个字段")
                .isEqualTo("qwen3:8b");
    }

    @Test
    void theSystemPromptRidesInTheMessageList() {
        JsonNode body = completeBody(AiReasoningEffort.DEFAULT, 512);

        assertThat(body.path("system").isMissingNode())
                .as("顶层 system 是 Anthropic 的形状,原生协议不收")
                .isTrue();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("SYS");
    }

    // --- 请求体:三处与 OpenAI 形状不同 -------------------------------------------------

    @Test
    void theBudgetIsNumPredictNotMaxTokens() {
        JsonNode body = completeBody(AiReasoningEffort.DEFAULT, 512);

        assertThat(body.has("max_tokens"))
                .as("原生协议没有 max_tokens;发了它也不报错,但预算就等于没设")
                .isFalse();
        assertThat(body.path("options").path("num_predict").asInt()).isEqualTo(512);
    }

    @Test
    void theRequestAlwaysStatesWhetherItStreams() {
        // Ollama 的 stream 默认是 true:不显式发,一个非流式调用会以 NDJSON 回来,
        // 而调用方在等一个完整的 JSON —— 结果是空回复。
        assertThat(completeBody(AiReasoningEffort.DEFAULT, 512).path("stream").asBoolean())
                .as("非流式也必须显式发 stream:false")
                .isFalse();
    }

    @Test
    void theStreamingRequestAsksForTheJsonItActuallyGets() {
        reply = NATIVE_NDJSON;

        AiChatClient.ChatResult result = client.stream(
                provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512, d -> { });

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(lastAccept.get())
                .as("原生流式回的是 application/json 的 NDJSON,不是 text/event-stream")
                .isEqualTo("application/json");
    }

    // --- 流式:一行一个对象 -------------------------------------------------------------

    @Test
    void aNativeStreamArrivesOneDeltaAtATime() {
        reply = NATIVE_NDJSON;
        List<String> deltas = new ArrayList<>();

        AiChatClient.ChatResult result = client.stream(
                provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512, deltas::add);

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(deltas)
                .as("每一行都是一次独立的 sink 调用;攒完一次性交出就不是流式了")
                .containsExactly("SE", "LE", "CT");
        assertThat(result.getText()).isEqualTo("SELECT");
    }

    @Test
    void blankAndMalformedLinesAreSkippedWithoutEndingTheStream() {
        reply = NATIVE_NDJSON;
        List<String> deltas = new ArrayList<>();

        AiChatClient.ChatResult result = client.stream(
                provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512, deltas::add);

        // 畸形行夹在第二个和第三个增量之间:后面的增量还在,就说明它没打断整个流。
        assertThat(result.getText()).as("畸形行之后的增量不能丢").isEqualTo("SELECT");
        assertThat(deltas).hasSize(3);
    }

    @Test
    void aDoneObjectEndsTheStream() {
        reply = "{\"message\":{\"content\":\"A\"},\"done\":false}\n"
                + "{\"message\":{\"content\":\"B\"},\"done\":true}\n"
                + "{\"message\":{\"content\":\"C\"},\"done\":false}\n";
        List<String> deltas = new ArrayList<>();

        AiChatClient.ChatResult result = client.stream(
                provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512, deltas::add);

        assertThat(deltas)
                .as("done:true 之后的行属于下一个请求,收进来就是拼接错误")
                .containsExactly("A", "B");
        assertThat(result.getText()).isEqualTo("AB");
    }

    // --- 思考开关:顶层布尔 think --------------------------------------------------------

    @Test
    void theDefaultEffortSendsNoThinkingFlagAtAll() {
        assertThat(completeBody(null, 512).has("think"))
                .as("跟随默认就该一个字段都不发,包括这条老数据(null)路径")
                .isFalse();
        assertThat(completeBody(AiReasoningEffort.DEFAULT, 512).has("think")).isFalse();
    }

    @Test
    void thinkingIsSwitchedWithATopLevelBoolean() {
        assertThat(completeBody(AiReasoningEffort.OFF, 512).path("think").asBoolean())
                .as("关闭要发 false:不发等于开着,本地推理模型会把预算花在思考上")
                .isFalse();
        assertThat(completeBody(AiReasoningEffort.LOW, 512).path("think").asBoolean()).isTrue();
        assertThat(completeBody(AiReasoningEffort.HIGH, 512).path("think").asBoolean()).isTrue();
    }

    @Test
    void theNativeRequestNeverCarriesTheOpenAiReasoningField() {
        JsonNode off = completeBody(AiReasoningEffort.OFF, 512);

        assertThat(off.has("reasoning_effort"))
                .as("原生协议不认 reasoning_effort;英文文档里的 low/high 档位串也只有较新版本认")
                .isFalse();
        assertThat(off.has("thinking")).isFalse();
    }

    // --- 探测与错误 ---------------------------------------------------------------------

    @Test
    void theProbeUsesTheNativeShapeAndStaysBare() {
        AiChatClient.ChatResult result = client.probe(provider(AiReasoningEffort.HIGH, 4096), null);

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(lastPath.get()).isEqualTo("/api/chat");
        try {
            JsonNode body = mapper.readTree(lastBody.get());
            assertThat(body.path("options").path("num_predict").asInt()).isEqualTo(1);
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(body.has("think"))
                    .as("探测是 max_tokens=1 的最小请求,带思考参数只会以端点自己的错误收场")
                    .isFalse();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theEndpointsOwnErrorTextIsPassedThrough() {
        status = 404;
        reply = "{\"error\":\"model 'qwen3:8b' not found, try pulling it first\"}";

        AiChatClient.ChatResult result =
                client.complete(provider(AiReasoningEffort.DEFAULT, 512), null, null, "ping", 512);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage())
                .as("原生端点的报错自带修复动作,吞掉它等于让用户猜")
                .contains("HTTP 404")
                .contains("model 'qwen3:8b' not found, try pulling it first");
    }
}
