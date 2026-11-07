package com.qqmu.jync.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.qqmu.jync.service.tls.TlsCertificateInspector;
import com.qqmu.jync.service.tls.TlsKeystoreStore;
import com.qqmu.jync.service.tls.TlsRuntimeState;

/**
 * Adds the application's own HTTPS connector when a certificate was saved.
 *
 * <p>The state bean is created while the web server is being built — during
 * {@code ServletWebServerApplicationContext.onRefresh()}, before the DataSource exists. That is
 * why it reads a file through {@link TlsKeystoreStore} and nothing else; a certificate held in
 * the metadata database would be unreadable at this point.
 *
 * <p>The connector is created by hand, so Boot's connector customizers never see it. The
 * parameter limit, form-post size and URI encoding are therefore copied explicitly from
 * {@link FormParameterLimitConfig} and {@code application.yml}; a test pins each value.
 *
 * <p>{@code throwOnFailure} stays at its default: Boot's
 * {@code checkThatConnectorsHaveStarted()} reports the port of the connector that actually
 * failed, while Tomcat's own failure report can name the primary port instead.
 */
@Configuration
public class TlsConnectorConfig {

    /** Mirrors {@code server.tomcat.max-http-form-post-size: 8MB}. */
    static final int MAX_POST_SIZE = 8 * 1024 * 1024;

    static final String PROTOCOLS = "TLSv1.2+TLSv1.3";

    @Bean
    public static TlsRuntimeState tlsRuntimeState(
            @Value("${app.tls.enabled:true}") boolean enabled,
            TlsKeystoreStore store,
            TlsCertificateInspector inspector) {
        return TlsRuntimeState.load(enabled, store, inspector);
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tlsConnectorCustomizer(
            TlsRuntimeState runtime, TlsKeystoreStore store,
            @Value("${server.address:}") String bindAddress) {
        return factory -> customize(factory, runtime, store, bindAddress);
    }

    static void customize(TomcatServletWebServerFactory factory, TlsRuntimeState runtime,
                          TlsKeystoreStore store) {
        customize(factory, runtime, store, "");
    }

    /**
     * {@code server.address} restricts the main connector to one interface; the hand-built
     * HTTPS connector must follow it, or enabling TLS would quietly re-expose the application
     * on every address the operator had deliberately excluded.
     */
    static void customize(TomcatServletWebServerFactory factory, TlsRuntimeState runtime,
                          TlsKeystoreStore store, String bindAddress) {
        runtime.active().ifPresent(config ->
                factory.addAdditionalTomcatConnectors(
                        httpsConnector(config, store.keystorePath(config), bindAddress)));
    }

    static Connector httpsConnector(com.qqmu.jync.service.tls.TlsConfig config, Path keystore) {
        return httpsConnector(config, keystore, "");
    }

    static Connector httpsConnector(com.qqmu.jync.service.tls.TlsConfig config, Path keystore,
                                    String bindAddress) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setPort(config.httpsPort());
        connector.setProperty("SSLEnabled", "true");
        connector.setURIEncoding(StandardCharsets.UTF_8.name());
        connector.setMaxParameterCount(FormParameterLimitConfig.MAX_PARAMETER_COUNT);
        connector.setMaxPostSize(MAX_POST_SIZE);
        if (bindAddress != null && !bindAddress.isBlank()) {
            try {
                ((AbstractProtocol<?>) connector.getProtocolHandler())
                        .setAddress(java.net.InetAddress.getByName(bindAddress.strip()));
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("Invalid server.address: " + bindAddress, e);
            }
        }

        SSLHostConfig ssl = new SSLHostConfig();
        ssl.setProtocols(PROTOCOLS);
        ssl.setCertificateKeystoreFile(keystore.toString());
        ssl.setCertificateKeystoreType(config.format().keystoreType());
        ssl.setCertificateKeystorePassword(config.passphrase());
        connector.addSslHostConfig(ssl);
        return connector;
    }
}
