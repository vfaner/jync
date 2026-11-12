package com.qqmu.jync.service.tls;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
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
 *
 * <p>Values are validated lazily, on first use, not in the constructor. This bean is created
 * before {@link TlsRuntimeState#load} gets to see {@code app.tls.enabled}; a contradictory or
 * malformed {@code app.tls.*} value must not fail the boot when TLS is disabled, because that
 * flag is the documented way back in to an instance whose TLS configuration is broken.
 */
@Component
public class TlsPathSource {

    static final int DEFAULT_HTTPS_PORT = 8443;

    private final String keystorePath;
    private final String keystorePassword;
    private final String pemCertificatePath;
    private final String pemKeyPath;
    private final String httpsPortValue;
    private final String redirectHttpValue;

    /** Parsed on first use; null until {@link #parsed()} completes. */
    private volatile Parsed parsed;

    public TlsPathSource(@Value("${app.tls.keystore-path:}") String keystorePath,
                         @Value("${app.tls.keystore-password:}") String keystorePassword,
                         @Value("${app.tls.pem-cert-path:}") String pemCertificatePath,
                         @Value("${app.tls.pem-key-path:}") String pemKeyPath,
                         @Value("${app.tls.https-port:8443}") String httpsPort,
                         @Value("${app.tls.redirect-http:true}") String redirectHttp) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword == null ? "" : keystorePassword;
        this.pemCertificatePath = pemCertificatePath;
        this.pemKeyPath = pemKeyPath;
        this.httpsPortValue = httpsPort;
        this.redirectHttpValue = redirectHttp;
    }

    /** No configured source, for callers that only know the managed TLS directory. */
    static TlsPathSource none() {
        return new TlsPathSource("", "", "", "", String.valueOf(DEFAULT_HTTPS_PORT), "true");
    }

    public boolean configured() {
        Parsed values = parsed();
        return values.keystore() != null || values.pemCertificate() != null;
    }

    public Optional<Path> keystore() {
        return Optional.ofNullable(parsed().keystore());
    }

    public String keystorePassword() {
        return keystorePassword;
    }

    public Optional<Path> pemCertificate() {
        return Optional.ofNullable(parsed().pemCertificate());
    }

    /** Empty for a combined bundle that carries the key in the certificate file. */
    public Optional<Path> pemKey() {
        return Optional.ofNullable(parsed().pemKey());
    }

    public int httpsPort() {
        return parsed().httpsPort();
    }

    public boolean redirectHttp() {
        return parsed().redirectHttp();
    }

    /**
     * Parses every value once. All accessors go through here, so the first one called reports any
     * malformed value; no caller ever observes half-parsed state.
     */
    private Parsed parsed() {
        Parsed values = parsed;
        if (values == null) {
            synchronized (this) {
                values = parsed;
                if (values == null) {
                    values = parse();
                    parsed = values;
                }
            }
        }
        return values;
    }

    private Parsed parse() {
        Path keystore = path(keystorePath);
        Path pemCertificate = path(pemCertificatePath);
        Path pemKey = path(pemKeyPath);

        if (keystore != null && (pemCertificate != null || pemKey != null)) {
            throw new TlsKeystoreException("error.tls.configSourceConflict");
        }
        if (pemCertificate == null && pemKey != null) {
            // A key alone cannot serve TLS; naming one without its certificate is a typo.
            throw new TlsKeystoreException("error.tls.configSourceConflict");
        }

        return new Parsed(keystore, pemCertificate, pemKey,
                parsePort(httpsPortValue), parseRedirect(redirectHttpValue));
    }

    private record Parsed(Path keystore, Path pemCertificate, Path pemKey,
                          int httpsPort, boolean redirectHttp) {
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
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> throw new TlsKeystoreException("error.tls.configInvalid");
        };
    }

    @Override
    public String toString() {
        // Never parses, so it cannot throw while an error is being logged. The passphrase is a
        // secret; a log line or an exception must never render it.
        return "TlsPathSource[keystore=" + display(keystorePath)
                + ", pemCertificate=" + display(pemCertificatePath)
                + ", pemKey=" + display(pemKeyPath)
                + ", httpsPort=" + display(httpsPortValue)
                + ", redirectHttp=" + display(redirectHttpValue)
                + ", keystorePassword=<redacted>]";
    }

    private static String display(String raw) {
        return raw == null || raw.isBlank() ? "null" : raw.strip();
    }
}
