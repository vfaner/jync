package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stream that goes silent must end, not block its caller forever.
 *
 * <p>The stall clock inside the read loop can only act when a line arrives, and the JDK
 * {@code HttpRequest} timeout covers the response headers only, so an upstream that sends one
 * event and then neither data nor a newline pins the reading thread indefinitely. The draft
 * endpoints run on a fixed thread pool, so a few such upstreams take the whole AI feature down.
 */
class AiChatClientStreamStallTest {

    private HttpServer server;
    private int port;
    /** Released in teardown so the handler thread can exit even when the client is stuck. */
    private final CountDownLatch releaseHandler = new CountDownLatch(1);
    private final AiChatClient client = new AiChatClient();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stall-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        releaseHandler.countDown();
        server.stop(0);
    }

    @Test
    @DisplayName("a stream that stops mid-flight is cut instead of hanging the caller")
    void aSilentStreamIsCut() throws Exception {
        AiProvider provider = new AiProvider();
        provider.setName("stalling-endpoint");
        provider.setProtocol(AiProtocol.OPENAI);
        provider.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        provider.setModel("test-model");
        provider.setTimeoutSeconds(1);

        ExecutorService caller = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stall-caller");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<AiChatClient.ChatResult> call = caller.submit(
                    () -> client.stream(provider, "key", null, "hi", 16, delta -> { }));

            // Before the fix this never returned: the caller blocks in readLine() forever.
            AiChatClient.ChatResult result = call.get(5, TimeUnit.SECONDS);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("stalled");
        } finally {
            caller.shutdownNow();
        }
    }

    /** Sends one SSE event, then stays silent without closing the exchange. */
    private void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            releaseHandler.await();
        } catch (Exception e) {
            // Teardown closes the exchange out from under us; nothing to report from a fixture.
        } finally {
            exchange.close();
        }
    }
}
