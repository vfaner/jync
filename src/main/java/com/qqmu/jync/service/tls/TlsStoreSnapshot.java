package com.qqmu.jync.service.tls;

import java.util.Optional;

/**
 * What the store can say without throwing.
 *
 * <p>The page must render even when the saved files are broken — that is exactly when the
 * administrator needs the removal button. {@code problemKey} carries the reason as an i18n key;
 * {@code sidecarPresent}/{@code keystorePresent} tell the page whether there is anything to
 * remove.
 *
 * @param serverPaths the certificate files a page-entered path names, or {@code null} for an
 *                    upload to the managed directory
 */
public record TlsStoreSnapshot(Optional<TlsConfig> config,
                               String problemKey,
                               boolean sidecarPresent,
                               boolean keystorePresent,
                               TlsConfiguredPaths serverPaths) {

    public TlsStoreSnapshot(Optional<TlsConfig> config, String problemKey,
                            boolean sidecarPresent, boolean keystorePresent) {
        this(config, problemKey, sidecarPresent, keystorePresent, null);
    }

    public static TlsStoreSnapshot empty() {
        return new TlsStoreSnapshot(Optional.empty(), null, false, false, null);
    }
}
