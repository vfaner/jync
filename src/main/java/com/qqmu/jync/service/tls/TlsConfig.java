package com.qqmu.jync.service.tls;

import java.time.Instant;

/**
 * One saved certificate generation, as read back from {@code tls.properties}.
 *
 * <p>{@code passphrase} is the decrypted keystore passphrase and lives only in memory. The
 * record's generated {@code toString()} would print it, and an exception or debug log that
 * renders this object must not leak it, so {@code toString()} is overridden.
 *
 * @param file generation file name, relative to the TLS directory
 */
public record TlsConfig(TlsKeystoreFormat format,
                        String file,
                        String passphrase,
                        int httpsPort,
                        boolean redirectHttp,
                        Instant updatedAt,
                        String sha256) {

    @Override
    public String toString() {
        return "TlsConfig[format=" + format
                + ", file=" + file
                + ", httpsPort=" + httpsPort
                + ", redirectHttp=" + redirectHttp
                + ", updatedAt=" + updatedAt
                + ", sha256=" + sha256
                + ", passphrase=<redacted>]";
    }
}
