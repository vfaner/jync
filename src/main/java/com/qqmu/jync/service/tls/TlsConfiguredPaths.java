package com.qqmu.jync.service.tls;

/**
 * Where the running certificate came from when it was configured in the file, not uploaded.
 *
 * <p>Only paths, never the passphrase: the page renders these rows, and the keystore passphrase
 * stays in {@link TlsConfig} where {@code toString()} redacts it. Exactly one mode is populated:
 * {@code keystorePath}, or {@code pemCertificatePath} with an optional {@code pemKeyPath}
 * (a combined bundle carries the key in the certificate file).
 */
public record TlsConfiguredPaths(String keystorePath,
                                 String pemCertificatePath,
                                 String pemKeyPath) {
}
