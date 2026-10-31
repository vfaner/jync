package com.qqmu.jync.service.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        assertThat(body.path("event").asText()).isEqualTo("jync.alert");
        assertThat(body.path("text").asText()).isEqualTo("hello");
    }

    @Test
    void theWeComAndDingTalkShapesUseTheirTextEnvelope() throws Exception {
        for (AlertWebhookFormat format : new AlertWebhookFormat[]{
                AlertWebhookFormat.WE_COM, AlertWebhookFormat.DING_TALK}) {
            JsonNode body = json.readTree(AlertNotifier.payload(format, "hi"));

            assertThat(body.path("msgtype").asText()).as(format.name()).isEqualTo("text");
            assertThat(body.path("text").path("content").asText()).isEqualTo("hi");
        }
    }

    @Test
    void theFeishuShapeUsesItsOwnEnvelope() throws Exception {
        JsonNode body = json.readTree(AlertNotifier.payload(AlertWebhookFormat.FEISHU, "hi"));

        assertThat(body.path("msg_type").asText()).isEqualTo("text");
        assertThat(body.path("content").path("text").asText()).isEqualTo("hi");
    }

    @Test
    void quotesAndNewlinesSurviveTheJsonEncoding() throws Exception {
        String text = "项目 \"订单\" 失败\n第二行 \\ 结尾";

        JsonNode body = json.readTree(AlertNotifier.payload(AlertWebhookFormat.GENERIC, text));

        assertThat(body.path("text").asText()).isEqualTo(text);
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
            assertThat(body.path("text").path("content").asText()).isEqualTo("告警文本");
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
}
