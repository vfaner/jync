package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletRegistration;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import com.qqmu.jync.service.tls.TestKeystores;
import com.qqmu.jync.service.tls.TlsCertificateInspector;
import com.qqmu.jync.service.tls.TlsConfiguredLoader;
import com.qqmu.jync.service.tls.TlsKeystoreFormat;
import com.qqmu.jync.service.tls.TlsKeystoreStore;
import com.qqmu.jync.service.tls.TlsPathSource;
import com.qqmu.jync.service.tls.TlsRuntimeState;
import com.qqmu.jync.util.CryptoUtil;

class TlsConnectorConfigTest {

    @TempDir
    Path dir;

    private TlsKeystoreStore store() {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("store-password");
        properties.setCryptoSalt("0123456789abcdef");
        return new TlsKeystoreStore(dir.toString(), new CryptoUtil(properties));
    }

    private TlsRuntimeState runtimeWithCertificate(int httpsPort) {
        TlsKeystoreStore store = store();
        store.save(TestKeystores.pkcs12(), TlsKeystoreFormat.PKCS12,
                TestKeystores.PASSPHRASE, httpsPort, false);
        return TlsRuntimeState.load(true, store, new TlsCertificateInspector());
    }

    private static TomcatWebServer start(TomcatServletWebServerFactory factory) {
        TomcatWebServer server = (TomcatWebServer) factory.getWebServer(okServlet());
        // getWebServer() builds and initializes the context but leaves the connectors removed;
        // start() adds them back and binds the ports.
        server.start();
        return server;
    }

    private static ServletContextInitializer okServlet() {
        return context -> {
            ServletRegistration.Dynamic servlet = context.addServlet("ok", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    response.setStatus(200);
                    response.getWriter().write("ok");
                }
            });
            servlet.addMapping("/*");
            servlet.setLoadOnStartup(1);
        };
    }

    private static Connector httpsConnector(TomcatWebServer server) {
        return Arrays.stream(server.getTomcat().getService().findConnectors())
                .filter(connector -> "https".equals(connector.getScheme()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HTTPS connector"));
    }

    @Test
    void servesHttpsWithTheUploadedCertificate() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsKeystoreStore store = store();
        TlsConnectorConfig.customize(factory, runtimeWithCertificate(freePort()), store);

        TomcatWebServer server = start(factory);
        try {
            Connector connector = httpsConnector(server);
            // The configured port is 0 only in this test; the connector binds a free port.
            HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(
                    "https://127.0.0.1:" + connector.getLocalPort() + "/")
                    .openConnection();
            connection.setSSLSocketFactory(trustAll().getSocketFactory());
            connection.setHostnameVerifier((host, session) -> true);

            assertThat(connection.getResponseCode()).isEqualTo(200);
            X509Certificate leaf = (X509Certificate) connection.getServerCertificates()[0];
            assertThat(leaf.getSubjectX500Principal().getName())
                    .contains("CN=misconfigured.example");
        } finally {
            server.stop();
        }
    }

    @Test
    void servesAConfiguredKeystoreOutsideTheTlsDirectory() throws Exception {
        // app.tls.keystore-path is the mounted-file mode: the connector must read that file
        // directly, with no copy into app.tls.directory.
        Path pinned = Files.createTempFile("jync-pinned-", ".p12");
        Files.write(pinned, TestKeystores.pkcs12());
        int port = freePort();
        TlsRuntimeState state = TlsRuntimeState.load(true, store(),
                new TlsCertificateInspector(),
                new TlsConfiguredLoader(new TlsPathSource(pinned.toString(), "changeit",
                        null, null, String.valueOf(port), "false")));

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsConnectorConfig.customize(factory, state, store());

        TomcatWebServer server = start(factory);
        try {
            Connector connector = httpsConnector(server);
            assertThat(connector.getLocalPort()).isEqualTo(port);
            HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(
                    "https://127.0.0.1:" + port + "/").openConnection();
            connection.setSSLSocketFactory(trustAll().getSocketFactory());
            connection.setHostnameVerifier((host, session) -> true);

            assertThat(connection.getResponseCode()).isEqualTo(200);
            X509Certificate leaf = (X509Certificate) connection.getServerCertificates()[0];
            assertThat(leaf.getSubjectX500Principal().getName())
                    .contains("CN=misconfigured.example");
        } finally {
            server.stop();
        }
    }

    @Test
    void keepsThePlainHttpConnectorWorking() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsConnectorConfig.customize(factory, runtimeWithCertificate(freePort()), store());

        TomcatWebServer server = start(factory);
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + server.getPort() + "/")
                            .openConnection();
            assertThat(connection.getResponseCode()).isEqualTo(200);
        } finally {
            server.stop();
        }
    }

    @Test
    void configuresTls12AndTls13() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsConnectorConfig.customize(factory, runtimeWithCertificate(freePort()), store());

        TomcatWebServer server = start(factory);
        try {
            SSLHostConfig ssl = httpsConnector(server).findSslHostConfigs()[0];
            assertThat(ssl.getProtocols()).contains("TLSv1.2", "TLSv1.3");
            org.apache.tomcat.util.net.SSLHostConfigCertificate certificate =
                    ssl.getCertificates().iterator().next();
            assertThat(certificate.getCertificateKeystoreType()).isEqualTo("PKCS12");
            assertThat(certificate.getCertificateKeystoreFile()).endsWith(".p12");
        } finally {
            server.stop();
        }
    }

    @Test
    void mirrorsTheMainConnectorLimits() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsConnectorConfig.customize(factory, runtimeWithCertificate(freePort()), store());

        TomcatWebServer server = start(factory);
        try {
            Connector connector = httpsConnector(server);
            assertThat(connector.getMaxParameterCount())
                    .isEqualTo(FormParameterLimitConfig.MAX_PARAMETER_COUNT);
            assertThat(connector.getMaxPostSize()).isEqualTo(8 * 1024 * 1024);
            assertThat(connector.getURIEncoding()).isEqualTo("UTF-8");
            assertThat(connector.getSecure()).isTrue();
        } finally {
            server.stop();
        }
    }

    @Test
    void bindsTheHttpsConnectorToTheConfiguredServerAddress() throws Exception {
        // server.address restricts the HTTP connector to one interface. The hand-built HTTPS
        // connector must follow, or enabling TLS re-exposes the app on every address.
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsConnectorConfig.customize(factory, runtimeWithCertificate(freePort()), store(),
                "127.0.0.1");

        TomcatWebServer server = start(factory);
        try {
            org.apache.coyote.AbstractProtocol<?> handler =
                    (org.apache.coyote.AbstractProtocol<?>) httpsConnector(server)
                            .getProtocolHandler();
            assertThat(handler.getAddress()).isNotNull()
                    .satisfies(address -> assertThat(address.isLoopbackAddress()).isTrue());
        } finally {
            server.stop();
        }
    }

    @Test
    void addsNoConnectorWhenNothingIsSaved() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsRuntimeState state = TlsRuntimeState.load(true, store(),
                new TlsCertificateInspector());
        TlsConnectorConfig.customize(factory, state, store());

        TomcatWebServer server = start(factory);
        try {
            assertThat(server.getTomcat().getService().findConnectors()).hasSize(1);
        } finally {
            server.stop();
        }
    }

    @Test
    void addsNoConnectorWhenTlsIsDisabled() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        TlsConnectorConfig.customize(factory, TlsRuntimeState.disabled(), store());

        TomcatWebServer server = start(factory);
        try {
            assertThat(server.getTomcat().getService().findConnectors()).hasSize(1);
        } finally {
            server.stop();
        }
    }

    @Test
    void failsToStartOnAPortConflictWithTheHttpsPort() throws Exception {
        int busyPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            busyPort = socket.getLocalPort();
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
            factory.setPort(0);
            TlsConnectorConfig.customize(factory, runtimeWithCertificate(busyPort), store());

            assertThatThrownBy(() -> start(factory))
                    .isInstanceOf(WebServerException.class)
                    .satisfies(e -> {
                        String message = e.getMessage() == null ? "" : e.getMessage();
                        assertThat(message).contains(String.valueOf(busyPort));
                    });
        }
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
