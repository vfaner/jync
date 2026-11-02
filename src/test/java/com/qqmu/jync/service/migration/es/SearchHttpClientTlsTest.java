package com.qqmu.jync.service.migration.es;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The opt-out from TLS verification must also cover hostname verification.
 *
 * <p>{@code java.net.http.HttpClient} forces endpoint identification back to {@code HTTPS} on
 * the JDK, so a trust-all {@code SSLContext} alone still rejects a certificate issued for a
 * different host name — exactly the self-signed setup users hit on an internal Easysearch.
 * These tests run a real HTTPS server whose certificate names a different host, and pin both
 * sides: verification stays on by default, and turns off when the connection says so.
 */
class SearchHttpClientTlsTest {

    private static final char[] PASS = "changeit".toCharArray();

    /** PKCS#12 with a self-signed RSA certificate whose SAN is misconfigured.example. */
    private static final String WRONG_HOST_KEYSTORE =
            "MIIKMAIBAzCCCdoGCSqGSIb3DQEHAaCCCcsEggnHMIIJwzCCBaoGCSqGSIb3DQEHAaCCBZsEggWXMIIFkzCCBY8GCyqGSIb3DQEM"+ 
            "CgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFCHsdxTc+UjItt5h9WPDPT76X0ENAgInEAIBIDAMBggq"+ 
            "hkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQ0qRuYwUKk8cEzfsrvhmdcwSCBNC5yzDZDNOqVmgAnQ5Uv0HD7sWW8EDvlWCVt+O+OWjD"+ 
            "22ctw5fXiVT1mHgc6F0tjGNPyXj7P4RTs1b4qvXygd9o39O8NUS2ud6rY0m7Hq4fQqYV9allpUdl1onbGaCGkg1wjwL2IgkMx3ak"+ 
            "HWNeGgQ+NSxoDpzVxYLaof+vju8VPvA5spwp3bIZqf4GoqD23/DZfepLlzTyqwNpaUogjYT2ntrDcAV3YKr9kQL9H3BLBrddZUzM"+ 
            "L5Dxeh7ingKNyc1UHYUd0LfeXL0tQpnV/+PlUIbbAoGd+hwV23hPKy0cRT64kAH98klYGS5XTlnudE+PILiFUZ05V59Jv2bJRIwB"+ 
            "1wMCuM6768S+NKOQDNYMKMwJ8hruL4PpBqEQwpPojom7qdWGCPwGhDCdmKe0nn6HkKYMMGxvvjwYhTvI4+apMlXKcKsIpyoMRtWu"+ 
            "shTkJQSczB6DAiHUA+MO1vxNBOHxbotO3V7kCxk70yfZLhUQVBsTGNhlLb2kw1b8l7dCZ4CBWyWldpY2gpTPcJj2PsD3PV/QcJAw"+ 
            "SW4/CwE3m7cw1n7xnmaiPPiiwSvjIOdSyEymkcQ+vJwdTF/D9K0YvgEl1XQfsKP4WvF0xu4FrnZR4hHqwFnBdV/yY5GwNUUsf+ex"+ 
            "vq6ql3Rhx4cLoIs6v8LXiohtVOo+ZOukpEKn0vOIa24HcVbogvfscdUmJ7LVc1ZUXFZz2WY6r+s69yZES8CVq03Sw+UHP+wJDo4c"+ 
            "jViFJj8hi0eUHx7YblU7RHxsS3uFQqxnjYEHkkqvEqva/vWkaA8lRFpJDD1J0EhT51vtdXq4Oa85qgx4Nx+IgPFZuI8vx6T7cCqr"+ 
            "wYCAqVtvCYov2XkddLS6PkhAEHDekvlA4gWAVit8jPlFHPrJre4NxToW+VPbSTq+EtftA/+flKoeIZp2e/tF9/nblgYFqIiwl7IT"+ 
            "KKcsJzSm2s2+/nyjFQGJFqH5/lNUF1fdFCdPl9VXdmmJRjCU2/No0arlyIhFPL6vZPCbIX0HVmmZdWIfd2BwOO04lyeKrcjeSTEt"+ 
            "MDxq4PknanXegqnJXm75JC1cZWIb7KQSYk5DtfoUVdW2ipP0gvvyL3RBMhm+//149Z6pLFwCvmYPR6j8U/roGHpaIEn/syW8wccr"+ 
            "uL2PldetHA3Gwyus0GaihGOujmDow5cyNd26OKEjz1Dko0AwqKY8Id/gOYHE2yPJZ1vlcTa5gs2wxIjC7GFGINPPd5lkXzNRFEg9"+ 
            "pDvx/3QCw9fioNzll5kddriBmZuctWODzn1km+vwhfKc245TnqDOfS1TL6MBokPq5VMignxkZhbKwQHpfUbRuUFrRfapXtEeF8HP"+ 
            "VRnaEg04XCSztoed3UGWzPTt5JiF/m+YRC20l00OnRSND8NCg3U+srUQ3mkV+yaaA85NWP0avJatGC3JZCQY/jZ9Vjp1Zlu/3yv0"+ 
            "iRajOzfBF8CYS2AiZ+uelgweuZ4bUcl7NER5HuL9mch4U3fZc7dN9CZREsYLVqOtmUu0Xg+NEt+OjWAPh4YRV6kKJE7KntA/wbMk"+ 
            "Jy+c/h+Cy5z6E8SjqWH8kARDZv9QMqnRrpGx3TP0PMKwkQXCDnsxKOKcW2zLg9CfGEYhig+jjdHvc5AiKHZS3DTVYsuynO50sEXj"+ 
            "xzE8MBcGCSqGSIb3DQEJFDEKHggAdABlAHMAdDAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkzNjI1OTQ1MTc0MIIEEQYJKoZIhvcN"+ 
            "AQcGoIIEAjCCA/4CAQAwggP3BgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFIQ9f1ituxrFmihv9RMW"+ 
            "RX6YXdgOAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQqA81m5nILQyaqq1oCaDW8YCCA4B0ifxAVtj1HTRz2fJM"+ 
            "D1KIPw9ugcJLjX2sBBjnT6jpQJ1nhQP35TLb/A+amUIHpfggcW/Zq2vbspNnwwEQFweXto7EfA5IEPpzneqbNYd1fZRwkaOPJZuE"+ 
            "LyF/KMPzE+qTDgIVxn/J9/74JSYNGPtT/HZYc9Df1MzgVVmA1vNDBcwWWHqMhYaPkrJ+M5U8dqpiEzQKiVXZbU9Ti/sTtKiMgoru"+ 
            "HD05ComDw8dyGb/aQLs6l0PgNFUPX4Z2aiU7Gk1k8G+BY3iQE2vkrws5mur9rAX3UkGtBzeciScSHwezB23Ll/SfUCgORBxU3Y0Q"+ 
            "2qVSdFU0f8rcE3UjkGcJeFa9MVzr4uFbovK+efzPZuLsQBspDOZUJqqqt+mlspjlkMSaTHMH+sjoHrAv/oM3HUUdot4pDYpMqBhg"+ 
            "wTugNOv0wYdDKRDCtApaODpXmFPlkgVY8HGP+BNz1VqkO19uphTEFCsdauOQqhJ/DLaBm3jmTyrJQT37ZPaaHVqrW4exT0okdp6y"+ 
            "h+WdYvDe7fYKXSE0KCwYYOgQQB830e4iDq5abz6C5dDyAgtgCMGwp2ei15EATNG/Sn+0870I98T9PhFu9UYKwFzCmhCM5G8l+J7d"+ 
            "CikcU99q2UlC3i5r3GLt+MrfE/Ys5pcPEQ0LRakodrrXpX9sSt885vPVU8zP4JAcTFFzbQm7jHq0+pZoL0+9jvy9Aer7Dh+/v6eU"+ 
            "XC6cMgU3hIOVcZAfDCz+luSHlJ+MqHeBvFFUG4NGwLwHlb3GcZS6AWgXQPTLO64E2OtScwhEgpqN7m7vkDnkhkRRWcitAstg0xU1"+ 
            "VOiiPh51fKFuwlzjR7ws/Qv049c8pCZKNSnmxiV3RMFEyXcKdmmWNaDRkbv1PUEqlOAYt0p0Ym/+0MJfEH6GGtmKj1pJgZJSh/ZI"+ 
            "qpEiBRbeOGiQK+5ULdf6yx1XrhKXLVtXzhXyO+A8CArnXj6qA02OcEXC5VLqiRx9daktDgaXUl9ZhtY1assPwZSQn/hODFGdgdCV"+ 
            "heajWzx7xugbBiBt16O0aU5MSnK+Tsair84Q0pMF69ngdh5ArLouxt4WOIn4nxsvwnEmRQoJcev0ohN2GSRqnU/W2S4md7cYGLv6"+ 
            "/aMDaFjpcILC9sD5qMQX3MWfPYOu5lC3q6bv6IG5OUNSOEtyS8gaCIBkcOZOEj3/xVRJrVP8fb24LjBNMDEwDQYJYIZIAWUDBAIB"+ 
            "BQAEINjtG4UEwYhDBdz043UBtCykJ8VoXDe9v2h0RQZ8AHr6BBQKdc813SW6VwEhSt8eU/Gpku+caQICJxA="                    + "";

    private HttpsServer server;
    private ExecutorService executor;

    @BeforeEach
    void start() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        keystore.load(new ByteArrayInputStream(
                Base64.getDecoder().decode(WRONG_HOST_KEYSTORE)), PASS);
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keystore, PASS);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private MigrationConnection connection(boolean verifyTls) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName("easysearch");
        connection.setKind(MigrationConnectionKind.EASYSEARCH);
        connection.setBaseUrl("https://127.0.0.1:" + server.getAddress().getPort());
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        connection.setVerifyTls(verifyTls);
        connection.setConnectTimeoutMs(3000);
        connection.setRequestTimeoutMs(5000);
        return connection;
    }

    @Test
    void verificationStaysOnByDefault() {
        try (SearchHttpClient client = new SearchHttpClient(connection(true), null, null)) {
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(MigrationHttpException.class);
        }
    }

    @Test
    void optingOutAcceptsACertificateForAnotherHost() {
        try (SearchHttpClient client = new SearchHttpClient(connection(false), null, null)) {
            assertThat(client.request("GET", "/", null, null).status()).isEqualTo(200);
        }
    }
}
