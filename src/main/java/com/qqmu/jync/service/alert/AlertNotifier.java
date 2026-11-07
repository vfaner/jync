package com.qqmu.jync.service.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AlertWebhookFormat;

import lombok.extern.slf4j.Slf4j;

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

    private final HttpClient client;
    private final ExecutorService executor;

    public AlertNotifier() {
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jync-alert-webhook");
            thread.setDaemon(true);
            return thread;
        });
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            payload(format, text), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Status only: the URL carries the room's secret token.
                log.debug("Alert webhook answered HTTP {}", response.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Alert webhook could not be reached: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** The JSON body for one message, in the selected product's envelope. */
    static String payload(AlertWebhookFormat format, String text) {
        String escaped = escape(text);
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
