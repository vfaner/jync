package com.qqmu.jync.service.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.sun.net.httpserver.HttpServer;

/**
 * The webhook's wire format and its failure behavior.
 *
 * <p>Delivery is best-effort by design: a chat server that is down must cost a log line, never
 * a stalled evaluator, and the URL itself never appears in a log because it is the credential.
 */
class AlertNotifierTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theGenericShapeCarriesTheMessageAsJson() throws Exception {
        JsonNode body = json.readTree(AlertNotifier.payload(AlertWebhookFormat.GENERIC, "hello"));

        assertThat(body.path("event").asString()).isEqualTo("jync.alert");
        assertThat(body.path("text").asString()).isEqualTo("hello");
    }

    @Test
    void theWeComAndDingTalkShapesUseTheirTextEnvelope() throws Exception {
        for (AlertWebhookFormat format : new AlertWebhookFormat[]{
                AlertWebhookFormat.WE_COM, AlertWebhookFormat.DING_TALK}) {
            JsonNode body = json.readTree(AlertNotifier.payload(format, "hi"));

            assertThat(body.path("msgtype").asString()).as(format.name()).isEqualTo("text");
            assertThat(body.path("text").path("content").asString()).isEqualTo("hi");
        }
    }

    @Test
    void theFeishuShapeUsesItsOwnEnvelope() throws Exception {
        JsonNode body = json.readTree(AlertNotifier.payload(AlertWebhookFormat.FEISHU, "hi"));

        assertThat(body.path("msg_type").asString()).isEqualTo("text");
        assertThat(body.path("content").path("text").asString()).isEqualTo("hi");
    }

    @Test
    void quotesAndNewlinesSurviveTheJsonEncoding() throws Exception {
        String text = "项目 \"订单\" 失败\n第二行 \\ 结尾";

        JsonNode body = json.readTree(AlertNotifier.payload(AlertWebhookFormat.GENERIC, text));

        assertThat(body.path("text").asString()).isEqualTo(text);
    }

    /**
     * WeCom caps a text message at 2048 bytes and rejects a longer one with HTTP 200 plus a
     * non-zero errcode — the alert would silently disappear. Cutting on a code-point boundary
     * keeps the JSON valid and the platform's limit satisfied.
     */
    @Test
    void aWeComMessageIsTrimmedToThePlatformsByteLimit() throws Exception {
        JsonNode body = json.readTree(
                AlertNotifier.payload(AlertWebhookFormat.WE_COM, "超".repeat(1000)));
        String content = body.path("text").path("content").asString();

        assertThat(content.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(AlertNotifier.WE_COM_MAX_CONTENT_BYTES);
        assertThat(content).startsWith("超").endsWith("…").doesNotContain("�");
    }

    @Test
    void aSuccessfulPostPutsThePayloadOnTheWire() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            AlertNotifier notifier = new AlertNotifier();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

            boolean sent = notifier.sendNow(url, AlertWebhookFormat.WE_COM, "告警文本");

            assertThat(sent).isTrue();
            JsonNode body = json.readTree(received.get());
            assertThat(body.path("text").path("content").asString()).isEqualTo("告警文本");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableEndpointCostsNoException() {
        AlertNotifier notifier = new AlertNotifier();

        boolean sent = notifier.sendNow("http://127.0.0.1:1/hook",
                AlertWebhookFormat.GENERIC, "x");

        assertThat(sent).isFalse();
    }

    @Test
    void aMalformedUrlCostsNoExceptionEither() {
        AlertNotifier notifier = new AlertNotifier();

        assertThat(notifier.sendNow("not a url", AlertWebhookFormat.GENERIC, "x")).isFalse();
    }

    /**
     * WeCom, DingTalk and Feishu answer a rejected message with HTTP 200 and put the verdict in
     * the body. Reading the status alone made the test button promise success and made a
     * silently dropped production alert look delivered.
     */
    @Test
    void aBusinessRejectionOnHttp200IsReportedAsAFailure() throws Exception {
        AlertNotifier notifier = new AlertNotifier();

        withService(200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}", url -> {
            assertThat(notifier.sendNow(url, AlertWebhookFormat.WE_COM, "告警")).isFalse();

            AlertNotifier.Delivery delivery = notifier.lastDelivery();
            assertThat(delivery).isNotNull();
            assertThat(delivery.ok()).isFalse();
            assertThat(delivery.detail()).contains("93000").contains("invalid webhook url");
        });
    }

    @Test
    void anErrcodeOfZeroIsAnAcceptedMessage() throws Exception {
        AlertNotifier notifier = new AlertNotifier();

        withService(200, "{\"errcode\":0,\"errmsg\":\"ok\"}", url -> {
            assertThat(notifier.sendNow(url, AlertWebhookFormat.DING_TALK, "x")).isTrue();
            assertThat(notifier.lastDelivery().ok()).isTrue();
        });
    }

    @Test
    void feishuUsesItsCodeFieldForTheVerdict() throws Exception {
        AlertNotifier notifier = new AlertNotifier();

        withService(200, "{\"code\":19001,\"msg\":\"param invalid\"}", url -> {
            assertThat(notifier.sendNow(url, AlertWebhookFormat.FEISHU, "x")).isFalse();
            assertThat(notifier.lastDelivery().detail()).contains("19001");
        });
    }

    @Test
    void aGenericEndpointIsJudgedByStatusAlone() throws Exception {
        AlertNotifier notifier = new AlertNotifier();

        withService(200, "{\"errcode\":93000}", url ->
                assertThat(notifier.sendNow(url, AlertWebhookFormat.GENERIC, "x")).isTrue());
    }

    @Test
    void anHttpErrorIsRememberedWithItsStatus() throws Exception {
        AlertNotifier notifier = new AlertNotifier();

        withService(500, "boom", url -> {
            assertThat(notifier.sendNow(url, AlertWebhookFormat.GENERIC, "x")).isFalse();
            assertThat(notifier.lastDelivery().detail()).contains("500");
        });
    }

    @Test
    void anUnreachableEndpointIsRememberedAsAFailedDelivery() {
        AlertNotifier notifier = new AlertNotifier();

        notifier.sendNow("http://127.0.0.1:1/hook", AlertWebhookFormat.GENERIC, "x");

        assertThat(notifier.lastDelivery()).isNotNull();
        assertThat(notifier.lastDelivery().ok()).isFalse();
        assertThat(notifier.lastDelivery().at()).isNotNull();
    }

    private interface Servlet {
        void run(String url) throws Exception;
    }

    private void withService(int status, String body, Servlet assertion) throws Exception {
        HttpServer server = responding(status, body);
        try {
            assertion.run("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer responding(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
