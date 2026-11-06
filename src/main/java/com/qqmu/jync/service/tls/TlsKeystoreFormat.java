package com.qqmu.jync.service.tls;

import java.util.Locale;
import java.util.Optional;

/**
 * The two keystore containers Tomcat can load as a server certificate.
 *
 * <p>The upload is recognized by its file name: browsers send an empty content type for these
 * extensions, and the real container is proven by actually opening the bytes in
 * {@link TlsCertificateInspector} — the name only decides which JDK {@code KeyStore} type to try.
 */
public enum TlsKeystoreFormat {

    PKCS12("PKCS12", "p12"),
    JKS("JKS", "jks");

    private final String keystoreType;
    private final String extension;

    TlsKeystoreFormat(String keystoreType, String extension) {
        this.keystoreType = keystoreType;
        this.extension = extension;
    }

    /** The {@code KeyStore.getInstance} type name. */
    public String keystoreType() {
        return keystoreType;
    }

    /** Extension used for the generation file the store writes. */
    public String extension() {
        return extension;
    }

    public static Optional<TlsKeystoreFormat> fromFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".p12") || lower.endsWith(".pfx")) {
            return Optional.of(PKCS12);
        }
        if (lower.endsWith(".jks")) {
            return Optional.of(JKS);
        }
        return Optional.empty();
    }
}
