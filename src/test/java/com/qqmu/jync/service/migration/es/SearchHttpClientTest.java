package com.qqmu.jync.service.migration.es;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.sun.net.httpserver.HttpServer;

class SearchHttpClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(String path, int status, String responseBody) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
    }

    private MigrationConnection connection(MigrationConnectionAuthType authType) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName("es");
        connection.setKind(MigrationConnectionKind.ELASTICSEARCH);
        connection.setBaseUrl(baseUrl() + "/");
        connection.setAuthType(authType);
        connection.setVerifyTls(true);
        connection.setConnectTimeoutMs(2000);
        connection.setRequestTimeoutMs(2000);
        return connection;
    }

    @Test
    void basicAuthUsesUtf8Base64() {
        respond("/_cluster/health", 200, "{\"status\":\"green\"}");
        MigrationConnection connection = connection(MigrationConnectionAuthType.BASIC);
        connection.setUsername("用户");
        SearchHttpClient client = new SearchHttpClient(connection, "pässwörd", null);

        assertThat(client.get("/_cluster/health").path("status").asText()).isEqualTo("green");

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "用户:pässwörd".getBytes(StandardCharsets.UTF_8));
        assertThat(authorization.get()).isEqualTo(expected);
    }

    @Test
    void apiKeyPairIsBase64EncodedButABase64KeyIsPreserved() {
        respond("/_cluster/health", 200, "{}");
        MigrationConnection pair = connection(MigrationConnectionAuthType.API_KEY);
        new SearchHttpClient(pair, null, "id:secret").get("/_cluster/health");
        assertThat(authorization.get()).isEqualTo("ApiKey "
                + Base64.getEncoder().encodeToString("id:secret".getBytes(StandardCharsets.UTF_8)));

        String alreadyEncoded = Base64.getEncoder()
                .encodeToString("id:secret".getBytes(StandardCharsets.UTF_8));
        new SearchHttpClient(connection(MigrationConnectionAuthType.API_KEY), null, alreadyEncoded)
                .get("/_cluster/health");
        assertThat(authorization.get()).isEqualTo("ApiKey " + alreadyEncoded);
    }

    @Test
    void noAuthSendsNoAuthorizationHeader() {
        respond("/_cluster/health", 200, "{}");
        new SearchHttpClient(connection(MigrationConnectionAuthType.NONE), null, null)
                .get("/_cluster/health");

        assertThat(authorization.get()).isNull();
    }

    @Test
    void nonHttpAndUserInfoBaseUrlsAreRejected() {
        MigrationConnection ftp = connection(MigrationConnectionAuthType.NONE);
        ftp.setBaseUrl("ftp://127.0.0.1:21");
        assertThatThrownBy(() -> new SearchHttpClient(ftp, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        MigrationConnection userInfo = connection(MigrationConnectionAuthType.NONE);
        userInfo.setBaseUrl("http://user:pass@127.0.0.1:9200");
        assertThatThrownBy(() -> new SearchHttpClient(userInfo, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non2xxResponseCarriesStatusAndRedactsSecrets() {
        respond("/missing", 404, "{\"error\":\"no such index pässwörd\"}");
        MigrationConnection connection = connection(MigrationConnectionAuthType.BASIC);
        connection.setUsername("alice");
        SearchHttpClient client = new SearchHttpClient(connection, "pässwörd", null);

        assertThatThrownBy(() -> client.get("/missing"))
                .isInstanceOf(MigrationHttpException.class)
                .satisfies(error -> {
                    MigrationHttpException http = (MigrationHttpException) error;
                    assertThat(http.getStatus()).isEqualTo(404);
                    assertThat(http.getMessage()).contains("GET /missing");
                    assertThat(http.getMessage()).doesNotContain("pässwörd");
                });
    }

    @Test
    void bulkSendsNdjsonAndReportsFailedItems() {
        respond("/_bulk", 200, "{\"errors\":true,\"items\":["
                + "{\"index\":{\"_id\":\"1\",\"status\":201}},"
                + "{\"index\":{\"_id\":\"2\",\"status\":400,"
                + "\"error\":{\"type\":\"mapper_parsing_exception\"}}}]}");
        SearchHttpClient client = new SearchHttpClient(
                connection(MigrationConnectionAuthType.NONE), null, null);

        BulkResult result = client.bulk(List.of(
                "{\"index\":{\"_index\":\"orders\",\"_id\":\"1\"}}",
                "{\"name\":\"one\"}",
                "{\"index\":{\"_index\":\"orders\",\"_id\":\"2\"}}",
                "{\"name\":\"two\"}"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.failedItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo("2");
                    assertThat(item.reason()).contains("mapper_parsing_exception");
                });
        assertThat(contentType.get()).startsWith("application/x-ndjson");
        assertThat(body.get()).endsWith("\n");
        assertThat(body.get()).contains("{\"_name\":\"one\"}".replace("_", ""))
                .contains("\"name\":\"one\"");
    }

    @Test
    void clearScrollSendsADeleteWithTheScrollId() {
        respond("/_search/scroll", 200, "{\"succeeded\":true}");
        SearchHttpClient client = new SearchHttpClient(
                connection(MigrationConnectionAuthType.NONE), null, null);

        client.clearScroll("abc123");

        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(body.get()).contains("\"scroll_id\"", "abc123");
    }

    @Test
    void redirectsAreNotFollowed() throws IOException {
        HttpServer second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger secondRequests = new AtomicInteger();
        second.createContext("/", exchange -> {
            secondRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        second.start();
        try {
            server.createContext("/redirect", exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().add("Location",
                        "http://127.0.0.1:" + second.getAddress().getPort() + "/stolen");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            SearchHttpClient client = new SearchHttpClient(
                    connection(MigrationConnectionAuthType.BASIC), "secret", null);

            assertThatThrownBy(() -> client.get("/redirect"))
                    .isInstanceOf(MigrationHttpException.class);
            assertThat(secondRequests.get()).isZero();
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            second.stop(0);
        }
    }
}
