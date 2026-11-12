package com.qqmu.jync.service.tls;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The certificate an operator names in the configuration file instead of uploading it.
 *
 * <p>Container deployments mount a certificate read-only and recreate the container on upgrade;
 * a certificate uploaded through the page lives in {@code app.tls.directory} and disappears with
 * the container. Pointing {@code app.tls.keystore-path} or {@code app.tls.pem-cert-path} at the
 * mounted files makes the deployment reproducible, and the page becomes read-only.
 *
 * <p>Parsing only — file existence and contents are proven by {@link TlsConfiguredLoader} at
 * startup, where a broken path fails the boot exactly like a corrupt saved keystore. Exactly one
 * source may be configured; a PEM key without its certificate is rejected because there is
 * nothing to pair it with.
 */
@Component
public class TlsPathSource {

    static final int DEFAULT_HTTPS_PORT = 8443;

    private final Path keystore;
    private final String keystorePassword;
    private final Path pemCertificate;
    private final Path pemKey;
    private final int httpsPort;
    private final boolean redirectHttp;

    public TlsPathSource(@Value("${app.tls.keystore-path:}") String keystorePath,
                         @Value("${app.tls.keystore-password:}") String keystorePassword,
                         @Value("${app.tls.pem-cert-path:}") String pemCertificatePath,
                         @Value("${app.tls.pem-key-path:}") String pemKeyPath,
                         @Value("${app.tls.https-port:8443}") String httpsPort,
                         @Value("${app.tls.redirect-http:true}") String redirectHttp) {
        this.keystore = path(keystorePath);
        this.keystorePassword = keystorePassword == null ? "" : keystorePassword;
        this.pemCertificate = path(pemCertificatePath);
        this.pemKey = path(pemKeyPath);

        boolean keystoreConfigured = this.keystore != null;
        boolean pemConfigured = this.pemCertificate != null || this.pemKey != null;
        if (keystoreConfigured && pemConfigured) {
            throw new TlsKeystoreException("error.tls.configSourceConflict");
        }
        if (this.pemCertificate == null && this.pemKey != null) {
            // A key alone cannot serve TLS; naming one without its certificate is a typo.
            throw new TlsKeystoreException("error.tls.configSourceConflict");
        }

        this.httpsPort = parsePort(httpsPort);
        this.redirectHttp = parseRedirect(redirectHttp);
    }

    /** No configured source, for callers that only know the managed TLS directory. */
    static TlsPathSource none() {
        return new TlsPathSource("", "", "", "", String.valueOf(DEFAULT_HTTPS_PORT), "true");
    }

    public boolean configured() {
        return keystore != null || pemCertificate != null;
    }

    public Optional<Path> keystore() {
        return Optional.ofNullable(keystore);
    }

    public String keystorePassword() {
        return keystorePassword;
    }

    public Optional<Path> pemCertificate() {
        return Optional.ofNullable(pemCertificate);
    }

    /** Empty for a combined bundle that carries the key in the certificate file. */
    public Optional<Path> pemKey() {
        return Optional.ofNullable(pemKey);
    }

    public int httpsPort() {
        return httpsPort;
    }

    public boolean redirectHttp() {
        return redirectHttp;
    }

    private static Path path(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Paths.get(raw.strip()).toAbsolutePath().normalize();
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_HTTPS_PORT;
        }
        int port;
        try {
            port = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new TlsKeystoreException("error.tls.portInvalid", e);
        }
        if (port < 1 || port > 65535) {
            throw new TlsKeystoreException("error.tls.portInvalid");
        }
        return port;
    }

    private static boolean parseRedirect(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (raw.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> throw new TlsKeystoreException("error.tls.configInvalid");
        };
    }

    @Override
    public String toString() {
        // The passphrase is a secret; a log line or an exception must never render it.
        return "TlsPathSource[keystore=" + keystore
                + ", pemCertificate=" + pemCertificate
                + ", pemKey=" + pemKey
                + ", httpsPort=" + httpsPort
                + ", redirectHttp=" + redirectHttp
                + ", keystorePassword=<redacted>]";
    }
}
