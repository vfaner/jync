package com.qqmu.jync.service.auth.oa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.OaAuthConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises the client against a real local HTTP server: the request shape (FORM vs JSON), the
 * response mapping (success field, user field, administrator marker), and the failure modes the
 * login form has to survive — unreachable host, timeout, oversized body, redirects.
 */
class HttpOaAuthClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestContentType = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
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
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            requestCount.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private OaAuthConfig config(String path) {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setLoginUrl(baseUrl() + path);
        config.setAllowInsecureHttp(true);
        config.setTimeoutMs(2000);
        return config;
    }

    private OaAuthClient client() {
        return new HttpOaAuthClient();
    }

    @Test
    void formPostsTheConfiguredFieldsAndMapsTheResponse() {
        respond("/login", 200, "application/json",
                "{\"success\":true,\"data\":{\"username\":\"alice\",\"isAdmin\":\"true\"}}");
        OaAuthConfig config = config("/login");
        config.setSuccessField("success");
        config.setSuccessValue("true");
        config.setUserField("data.username");
        config.setAdminField("data.isAdmin");
        config.setAdminValue("true,1");

        OaAuthResult result = client().authenticate(config, "alice", "s3cr3t!&");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getUsername()).isEqualTo("alice");
        assertThat(result.isAdmin()).isTrue();
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestContentType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(requestBody.get()).contains("username=alice");
        // The password is URL-encoded, not dropped or mangled.
        assertThat(requestBody.get()).contains("password=s3cr3t%21%26");
    }

    @Test
    void jsonFormatSendsTheConfiguredFieldNamesAsJson() {
        respond("/login", 200, "application/json", "{\"code\":0,\"uid\":\"bob\"}");
        OaAuthConfig config = config("/login");
        config.setRequestFormat(OaAuthConfig.RequestFormat.JSON);
        config.setUsernameField("user");
        config.setPasswordField("pass");
        config.setSuccessField("code");
        config.setSuccessValue("0");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "bob", "pw");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getUsername()).isEqualTo("bob");
        assertThat(result.isAdmin()).isFalse();
        assertThat(requestContentType.get()).startsWith("application/json");
        assertThat(requestBody.get()).contains("\"user\":\"bob\"").contains("\"pass\":\"pw\"");
    }

    @Test
    void withoutASuccessFieldOnlyA2xxResponseSucceeds() {
        respond("/login", 401, "application/json", "{\"uid\":\"alice\"}");
        OaAuthConfig config = config("/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "wrong");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.rejected");
    }

    @Test
    void aConfiguredSuccessFieldDecidesEvenWhenHttpSaysOtherwise() {
        respond("/login", 500, "application/json", "{\"result\":\"OK\",\"uid\":\"alice\"}");
        OaAuthConfig config = config("/login");
        config.setSuccessField("result");
        config.setSuccessValue("OK");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getUsername()).isEqualTo("alice");
    }

    @Test
    void aSuccessfulResponseWithoutAUserNameIsAFailure() {
        respond("/login", 200, "application/json", "{\"ok\":true}");
        OaAuthConfig config = config("/login");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.user.missing");
    }

    /**
     * The OA response is remote text, not a trusted identifier: an embedded control character
     * would travel into the session principal and the nav. Header mode already strips them;
     * the response-based modes must not be the exception.
     */
    @Test
    void controlCharactersInTheReturnedUserNameAreStripped() {
        respond("/login", 200, "application/json", "{\"uid\":\"al\\u0001ice\"}");
        OaAuthConfig config = config("/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getUsername()).isEqualTo("alice");
    }

    @Test
    void aUserNameMadeOnlyOfControlCharactersIsAFailure() {
        respond("/login", 200, "application/json", "{\"uid\":\"\\u0001\\u0002\"}");
        OaAuthConfig config = config("/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.user.missing");
    }

    @Test
    void administratorMarkerAcceptsAnyValueInTheCommaList() {
        respond("/login", 200, "application/json", "{\"uid\":\"carol\",\"role\":\"1\"}");
        OaAuthConfig config = config("/login");
        config.setUserField("uid");
        config.setAdminField("role");
        config.setAdminValue("true, 1");

        OaAuthResult result = client().authenticate(config, "carol", "pw");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isAdmin()).isTrue();
    }

    @Test
    void anOversizedResponseIsRejectedInsteadOfBuffered() {
        String huge = "{\"uid\":\"alice\",\"pad\":\"" + "x".repeat(70 * 1024) + "\"}";
        respond("/login", 200, "application/json", huge);
        OaAuthConfig config = config("/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.responseTooLarge");
    }

    @Test
    void aTimeoutIsReportedAsUnreachable() throws Exception {
        server.createContext("/slow", exchange -> {
            requestCount.incrementAndGet();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        OaAuthConfig config = config("/slow");
        config.setTimeoutMs(300);
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.unreachable");
    }

    @Test
    void redirectsAreNeverFollowed() {
        AtomicInteger redirectedHits = new AtomicInteger();
        server.createContext("/redirected", exchange -> {
            redirectedHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/login", exchange -> {
            requestCount.incrementAndGet();
            exchange.getResponseHeaders().add("Location", baseUrl() + "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        OaAuthConfig config = config("/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(redirectedHits.get()).isZero();
    }

    @Test
    void plainHttpIsRefusedBeforeAnyRequestWhenConsentWasNeverGiven() {
        respond("/login", 200, "application/json", "{\"uid\":\"alice\"}");
        OaAuthConfig config = config("/login");
        config.setAllowInsecureHttp(false);
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.url.notHttps");
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void malformedJsonIsReportedAsABadResponse() {
        respond("/login", 200, "application/json", "not json at all");
        OaAuthConfig config = config("/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.badResponse");
    }

    @Test
    void anUnreachableHostIsReportedWithoutThrowing() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setAllowInsecureHttp(true);
        config.setTimeoutMs(300);
        // Port 1 on loopback: connection refused, not a timeout.
        config.setLoginUrl("http://127.0.0.1:1/login");
        config.setUserField("uid");

        OaAuthResult result = client().authenticate(config, "alice", "pw");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.unreachable");
    }
}
