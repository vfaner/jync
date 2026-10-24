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
import com.sun.net.httpserver.HttpServer;

class HttpTicketValidationClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
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

    private void respond(String path, int status, String json) {
        server.createContext(path, exchange -> {
            requestCount.incrementAndGet();
            method.set(exchange.getRequestMethod());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private OaAuthConfig config(String path) {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);
        config.setValidateUrl(baseUrl() + path);
        config.setAllowInsecureHttp(true);
        config.setTimeoutMs(2000);
        config.setUserField("data.username");
        return config;
    }

    private TicketValidationClient client() {
        return new HttpTicketValidationClient();
    }

    @Test
    void getSendsTheTicketAsAQueryParameterAndMapsTheUser() {
        respond("/validate", 200, "{\"data\":{\"username\":\"alice\",\"isAdmin\":\"true\"}}");
        OaAuthConfig config = config("/validate");
        config.setAdminField("data.isAdmin");
        config.setAdminValue("true,1");

        OaAuthResult result = client().validate(config, "t-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getUsername()).isEqualTo("alice");
        assertThat(result.isAdmin()).isTrue();
        assertThat(method.get()).isEqualTo("GET");
        assertThat(rawQuery.get()).isEqualTo("ticket=t-1");
    }

    @Test
    void postSendsTheTicketAsAFormField() {
        respond("/validate", 200, "{\"data\":{\"username\":\"alice\"}}");
        OaAuthConfig config = config("/validate");
        config.setValidateMethod(OaAuthConfig.ValidateMethod.POST);
        config.setTicketParam("st");

        OaAuthResult result = client().validate(config, "t-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(method.get()).isEqualTo("POST");
        assertThat(body.get()).isEqualTo("st=t-1");
    }

    @Test
    void theTicketIsEncodedExactlyOnce() {
        respond("/validate", 200, "{\"data\":{\"username\":\"alice\"}}");

        client().validate(config("/validate"), "a&b=c+d 中文");

        assertThat(rawQuery.get())
                .isEqualTo("ticket=a%26b%3Dc%2Bd+%E4%B8%AD%E6%96%87");
    }

    @Test
    void aNon2xxResponseIsRejectedEvenWhenTheBodySaysSuccess() {
        respond("/validate", 500, "{\"ok\":true,\"data\":{\"username\":\"alice\"}}");
        OaAuthConfig config = config("/validate");
        config.setSuccessField("ok");
        config.setSuccessValue("true");

        assertThat(client().validate(config, "t-1").isSuccess()).isFalse();
    }

    @Test
    void aMismatchedSuccessValueIsRejected() {
        respond("/validate", 200, "{\"ok\":false,\"data\":{\"username\":\"alice\"}}");
        OaAuthConfig config = config("/validate");
        config.setSuccessField("ok");
        config.setSuccessValue("true");

        assertThat(client().validate(config, "t-1").isSuccess()).isFalse();
    }

    @Test
    void aResponseWithoutTheConfiguredUserFieldIsAFailure() {
        respond("/validate", 200, "{\"data\":{}}");

        OaAuthResult result = client().validate(config("/validate"), "t-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.user.missing");
    }

    /** Same rule as the password client: the portal's response is text, not a trusted name. */
    @Test
    void controlCharactersInTheReturnedUserNameAreStripped() {
        respond("/validate", 200, "{\"data\":{\"username\":\"al\\u0001ice\"}}");

        OaAuthResult result = client().validate(config("/validate"), "t-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getUsername()).isEqualTo("alice");
    }

    @Test
    void aUserNameMadeOnlyOfControlCharactersIsAFailure() {
        respond("/validate", 200, "{\"data\":{\"username\":\"\\u0001\\u0002\"}}");

        OaAuthResult result = client().validate(config("/validate"), "t-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.user.missing");
    }

    @Test
    void aRedirectIsNotFollowed() {
        respond("/validate", 302, "");
        server.createContext("/elsewhere", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        OaAuthResult result = client().validate(config("/validate"), "t-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void anOversizedResponseIsRefused() {
        respond("/validate", 200, "x".repeat(70 * 1024));

        OaAuthResult result = client().validate(config("/validate"), "t-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorKey()).isEqualTo("error.oa.responseTooLarge");
    }
}
