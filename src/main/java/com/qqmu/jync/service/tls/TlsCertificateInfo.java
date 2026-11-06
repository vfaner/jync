package com.qqmu.jync.service.tls;

import java.time.Instant;

/**
 * What the page shows about the certificate inside a keystore.
 *
 * <p>Only the leaf is described: the chain is served by Tomcat from the keystore unchanged, and
 * listing every link would imply the page validates it, which it does not.
 *
 * @param sha256Fingerprint lower-case hex SHA-256 of the leaf's DER encoding
 */
public record TlsCertificateInfo(String subject,
                                 String issuer,
                                 String alias,
                                 String publicKeyAlgorithm,
                                 String sha256Fingerprint,
                                 Instant notBefore,
                                 Instant notAfter) {

    public boolean isExpired(Instant now) {
        return !now.isBefore(notAfter);
    }

    public boolean isNotYetValid(Instant now) {
        return now.isBefore(notBefore);
    }

    public boolean expiresWithin(Instant now, java.time.Duration window) {
        return !isExpired(now) && !now.plus(window).isBefore(notAfter);
    }
}
