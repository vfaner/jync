package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.service.tls.TestKeystores;
import com.qqmu.jync.service.tls.TlsKeystoreFormat;
import com.qqmu.jync.service.tls.TlsKeystoreStore;
import com.qqmu.jync.service.tls.TlsRuntimeState;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The certificate must be readable before the DataSource exists.
 *
 * <p>The embedded connector is created during {@code ServletWebServerApplicationContext.onRefresh()},
 * which runs before JPA and the metadata database are initialized. This full-context test saves a
 * certificate on disk first and starts the real application: if anything in the TLS path had grown
 * a dependency on a repository, the context would fail here, and if the connector were added after
 * startup the port would answer nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:tlsstartup;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "sync.crypto-password=tls-startup-password",
        "sync.crypto-salt=0123456789abcdef"
})
class TlsStartupOrderingTest {

    /** Written before the context exists, exactly as a previous run's page would have left it. */
    private static final Path TLS_DIR = createSavedCertificate();

    private static final int HTTPS_PORT = readSavedPort();

    @DynamicPropertySource
    static void tlsProperties(DynamicPropertyRegistry registry) {
        registry.add("app.tls.directory", TLS_DIR::toString);
    }

    @Autowired
    private TlsRuntimeState runtime;

    @Autowired
    private TlsKeystoreStore store;

    @Autowired
    private ServletWebServerApplicationContext context;

    @Test
    void contextStartsAndActivatesTheSavedCertificate() {
        assertThat(runtime.enabled()).isTrue();
        assertThat(runtime.problemKey()).isNull();
        assertThat(runtime.active()).isPresent();
        assertThat(runtime.active().orElseThrow().httpsPort()).isEqualTo(HTTPS_PORT);
        assertThat(runtime.info()).isPresent();
        assertThat(runtime.info().orElseThrow().subject()).contains("CN=misconfigured.example");
    }

    @Test
    void theHttpsConnectorIsListeningBeforeTheContextIsReady() throws Exception {
        WebServer server = context.getWebServer();
        assertThat(server).isInstanceOf(TomcatWebServer.class);
        Connector https = Arrays.stream(((TomcatWebServer) server).getTomcat().getService()
                        .findConnectors())
                .filter(connector -> "https".equals(connector.getScheme()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HTTPS connector"));

        // The saved port, not a random one: the connector was built from the sidecar.
        assertThat(https.getLocalPort()).isEqualTo(HTTPS_PORT);

        HttpsURLConnection connection = (HttpsURLConnection) new URL(
                "https://127.0.0.1:" + HTTPS_PORT + "/login/local").openConnection();
        connection.setSSLSocketFactory(trustAll().getSocketFactory());
        connection.setHostnameVerifier((host, session) -> true);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            X509Certificate leaf = (X509Certificate) connection.getServerCertificates()[0];
            assertThat(leaf.getSubjectX500Principal().getName())
                    .contains("CN=misconfigured.example");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void theStartupSnapshotIsImmutable() {
        // The page can delete the files afterwards; the connector keeps serving this certificate
        // until the next restart, which is exactly what the page tells the reader.
        store.delete();

        try {
            assertThat(runtime.active()).isPresent();
            assertThat(runtime.info()).isPresent();
        } finally {
            restoreSavedCertificate();
        }
    }

    private static Path createSavedCertificate() {
        try {
            Path dir = Files.createTempDirectory("jync-tls-startup-");
            TlsKeystoreStore store = new TlsKeystoreStore(dir.toString(), crypto());
            store.save(TestKeystores.pkcs12(), TlsKeystoreFormat.PKCS12, TestKeystores.PASSPHRASE,
                    freePort(), true);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare the TLS fixture", e);
        }
    }

    private static void restoreSavedCertificate() {
        new TlsKeystoreStore(TLS_DIR.toString(), crypto()).save(TestKeystores.pkcs12(),
                TlsKeystoreFormat.PKCS12, TestKeystores.PASSPHRASE, HTTPS_PORT, true);
    }

    private static int readSavedPort() {
        return new TlsKeystoreStore(TLS_DIR.toString(), crypto()).requireConfig().httpsPort();
    }

    private static CryptoUtil crypto() {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("tls-startup-password");
        properties.setCryptoSalt("0123456789abcdef");
        return new CryptoUtil(properties);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }
}
