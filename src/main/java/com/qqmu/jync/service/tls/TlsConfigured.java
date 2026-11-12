package com.qqmu.jync.service.tls;

/**
 * The certificate loaded from {@code app.tls.*} paths: the config the connector needs plus the
 * paths the page shows.
 *
 * @param config synthesized like an upload's config; its {@code file} is an absolute path
 * @param paths the configured source, for the read-only page
 */
public record TlsConfigured(TlsConfig config, TlsConfiguredPaths paths) {

    @Override
    public String toString() {
        return "TlsConfigured[" + paths + ", config=" + config + "]";
    }
}
