package com.qqmu.jync.service.tls;

import java.time.Instant;

/**
 * What the system configuration page shows about TLS.
 *
 * <p>Four independent facts, because conflating them is how a page lies to its reader:
 * <strong>saved</strong> (a configuration exists on disk), <strong>active</strong> (the running
 * connector was built from one), <strong>pending restarts</strong> (the two differ), and
 * <strong>removal pending</strong> (nothing is saved but the old certificate is still being
 * served). Nothing here carries the passphrase: the record has no such field, so neither the
 * template nor a log line can print it.
 *
 * @param problemKey why the saved configuration is unusable, or {@code null}
 */
public record TlsPageState(boolean saved,
                           boolean active,
                           boolean pendingRestart,
                           boolean removalPending,
                           boolean disabled,
                           boolean canRemove,
                           boolean usingDefaultCryptoKey,
                           String problemKey,
                           TlsCertificateInfo certificate,
                           TlsCertificateInfo activeCertificate,
                           boolean expired,
                           boolean expiresSoon,
                           Integer httpsPort,
                           boolean redirectHttp,
                           Instant updatedAt) {
}
