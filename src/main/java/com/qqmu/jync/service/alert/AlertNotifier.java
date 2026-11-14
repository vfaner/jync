package com.qqmu.jync.service.alert;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AlertWebhookFormat;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Posts one alert to the configured webhook.
 *
 * <p>Delivery is best-effort and out of band: {@link #send} hands the work to a single daemon
 * thread, so an unreachable chat server can never hold up the evaluator or a sync cycle. The
 * URL is the credential, so it is never written to a log line — only the failure itself is.
 */
@Service
@Slf4j
public class AlertNotifier {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** Enough for a chat platform's verdict envelope; anything larger is not one. */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** One failure log line per URL per interval: a down channel must not flood the log. */
    static final Duration WARN_INTERVAL = Duration.ofMinutes(10);

    /**
     * WeCom rejects a text message whose {@code content} exceeds 2048 bytes — with HTTP 200 and
     * a non-zero errcode, so the alert would silently disappear.
     */
    static final int WE_COM_MAX_CONTENT_BYTES = 2048;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One delivery's outcome, shown on the settings page so a silent channel is visible. */
    public record Delivery(Instant at, boolean ok, String detail) {
    }

    private final HttpClient client;
    private final ExecutorService executor;
    private final AtomicReference<Delivery> lastDelivery = new AtomicReference<>();
    private final AtomicReference<String> lastWarnKey = new AtomicReference<>();
    private final AtomicLong lastWarnAt = new AtomicLong();

    public AlertNotifier() {
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jync-alert-webhook");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The most recent delivery attempt, or {@code null} before the first one. */
    public Delivery lastDelivery() {
        return lastDelivery.get();
    }

    /** Queues the alert; returns immediately. */
    public void send(String url, AlertWebhookFormat format, String text) {
        executor.execute(() -> sendNow(url, format, text));
    }

    /**
     * Posts synchronously and reports whether the endpoint accepted the message.
     *
     * <p>Used by the settings page's test button, which must tell the administrator now whether
     * the URL works. Every failure is swallowed into {@code false}: an alert channel that is
     * down is never allowed to break the caller.
     */
    public boolean sendNow(String url, AlertWebhookFormat format, String text) {
        Delivery delivery;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            payload(format, text), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream stream = response.body()) {
                body = new String(stream.readNBytes(MAX_RESPONSE_BYTES),
                        StandardCharsets.UTF_8);
            }
            int status = response.statusCode();
            delivery = status < 200 || status >= 300
                    ? new Delivery(Instant.now(), false, "HTTP " + status)
                    : verdict(format, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delivery = new Delivery(Instant.now(), false, "interrupted");
        } catch (Exception e) {
            delivery = new Delivery(Instant.now(), false,
                    detailOf(e, url));
        }
        lastDelivery.set(delivery);
        if (!delivery.ok()) {
            warnOnce(url, delivery.detail());
        }
        return delivery.ok();
    }

    /**
     * The verdict for a 2xx response.
     *
     * <p>The chat platforms answer a rejected message with HTTP 200 and put the reason in the
     * body ({@code errcode}/{@code code}); trusting the status alone reported a dropped alert
     * as delivered. A body that is empty, not JSON, or carries no such field cannot be judged
     * and is accepted — the generic format has no agreed envelope at all.
     */
    private static Delivery verdict(AlertWebhookFormat format, String body) {
        String field = switch (format) {
            case DING_TALK, WE_COM -> "errcode";
            case FEISHU -> "code";
            default -> null;
        };
        if (field == null || body == null || body.isBlank()) {
            return new Delivery(Instant.now(), true, null);
        }
        JsonNode code;
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return new Delivery(Instant.now(), true, null);
            }
            code = root.get(field);
            if (code == null || !code.isNumber()) {
                return new Delivery(Instant.now(), true, null);
            }
            if (code.asInt() == 0) {
                return new Delivery(Instant.now(), true, null);
            }
            String reason = firstText(root, "errmsg", "msg", "message");
            return new Delivery(Instant.now(), false,
                    field + " " + code.asInt() + (reason == null ? "" : ": " + reason));
        } catch (RuntimeException e) {
            return new Delivery(Instant.now(), true, null);
        }
    }

    private static String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && node.isString() && !node.asString().isBlank()) {
                return truncate(node.asString());
            }
        }
        return null;
    }

    /** Keeps a platform message on one line and small enough for the settings page. */
    private static String truncate(String value) {
        String flat = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * A failed delivery is an operator-visible event, but a channel that is down would write a
     * line every evaluation. One line per URL per {@link #WARN_INTERVAL} is the compromise; the
     * settings page shows the most recent outcome regardless.
     */
    private void warnOnce(String url, String detail) {
        long now = System.currentTimeMillis();
        // A hash keeps the credential-bearing URL out of both the log and the key.
        String key = Integer.toHexString(url.hashCode());
        String previous = lastWarnKey.get();
        long previousAt = lastWarnAt.get();
        if (key.equals(previous) && now - previousAt < WARN_INTERVAL.toMillis()) {
            return;
        }
        lastWarnKey.set(key);
        lastWarnAt.set(now);
        log.warn("Alert webhook delivery failed: {}", detail);
    }

    /** Never echoes the URL: it is the room's secret token. */
    private static String detailOf(Exception e, String url) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message.replace(url, "<webhook>");
    }

    /** The JSON body for one message, in the selected product's envelope. */
    static String payload(AlertWebhookFormat format, String text) {
        String content = format == AlertWebhookFormat.WE_COM
                ? fitBytes(text, WE_COM_MAX_CONTENT_BYTES) : text;
        String escaped = escape(content);
        switch (format) {
            case WE_COM:
            case DING_TALK:
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escaped + "\"}}";
            case FEISHU:
                return "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escaped + "\"}}";
            case GENERIC:
            default:
                return "{\"event\":\"jync.alert\",\"text\":\"" + escaped + "\"}";
        }
    }

    /**
     * Cuts a message to fit a byte limit, ending on a code-point boundary so the result is still
     * valid UTF-8, and marking the cut so the reader knows the alert continues.
     */
    static String fitBytes(String text, int maxBytes) {
        if (text == null) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes - "…".getBytes(StandardCharsets.UTF_8).length;
        // A continuation byte (10xxxxxx) means the cut landed inside a character.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + "…";
    }

    /** Minimal JSON string escaping — enough for the text field of a fixed envelope. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
