package com.qqmu.jync.service.auth.oa;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

import com.qqmu.jync.model.OaAuthConfig;

/**
 * The JDK HTTP plumbing shared by the password and ticket clients.
 *
 * <p>One client for the whole process: it owns a selector thread and a connection pool, and the
 * JDK expects instances to be reused. Redirects are never followed — an internal endpoint must
 * not be able to bounce a password or a ticket to another host.
 */
final class OaHttp {

    static final int DEFAULT_TIMEOUT_MS = 5000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private OaHttp() {
    }

    static HttpClient client() {
        return CLIENT;
    }

    /**
     * The URL rule both calls share: http(s) only, and plain http only after explicit consent.
     *
     * @return the i18n key for a refused URL, or null when it may be called
     */
    static String schemeErrorKey(URI uri, OaAuthConfig config) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "error.oa.url.invalid";
        }
        if (scheme.equals("http") && !config.isAllowInsecureHttp()) {
            return "error.oa.url.notHttps";
        }
        return null;
    }

    /** The per-call deadline; the connect timeout on the shared client only bounds the handshake. */
    static int timeoutMs(OaAuthConfig config) {
        return config.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : config.getTimeoutMs();
    }
}
