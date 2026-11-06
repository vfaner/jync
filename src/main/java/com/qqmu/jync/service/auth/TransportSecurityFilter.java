package com.qqmu.jync.service.auth;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.qqmu.jync.util.ClientIpResolver;
import com.qqmu.jync.util.HttpsSupport;

/**
 * Transport security: HTTPS enforcement and the headers that only make sense with it.
 *
 * <p>The switch exists because most deployments sit behind a reverse proxy that already
 * terminates TLS; turning enforcement on there without telling the app which peers are
 * proxies would reject everything. When it is on, a request that did not arrive over TLS is
 * answered 403, with two exemptions:
 *
 * <ul>
 *   <li><strong>Loopback.</strong> Same-host operations and the unit tests dial the app
 *       directly; the ciphertext question does not arise. This is loopback proper, not the
 *       configured proxy list: a remote proxy relaying plaintext is exactly what enforcement
 *       refuses.</li>
 *   <li><strong>An unauthenticated {@code GET /login}.</strong> This is the target of both
 *       the Docker and the Kubernetes health probes, and a probe crash loop is not a
 *       security win. It carries no password and no Cookie header, so nothing secret leaves
 *       the process; the password arrives in the POST, which is never exempt. A request
 *       that <em>does</em> carry a Cookie (a signed-in browser) is refused like every
 *       other page, because that request would otherwise put the session ID on the wire.</li>
 * </ul>
 *
 * <p>The switch is read per request through a {@link BooleanSupplier}: it is page-editable
 * now, and a value captured at construction would keep enforcing the old setting until the
 * next restart. It is consulted only for a plaintext, non-exempt request, so HTTPS traffic
 * and probes never pay for the lookup.
 *
 * <p>{@code X-Forwarded-Proto} is believed only from a peer already named as a trusted
 * proxy. That is the same rule {@link ClientIpResolver} applies to {@code X-Forwarded-For}:
 * every LAN client connects directly, so an unchecked header would let any of them claim
 * HTTPS for a plain request.
 *
 * <p>HSTS is written whenever the effective scheme is HTTPS, including when TLS was
 * terminated by a trusted proxy. The header is about the browser-to-public-host leg, which
 * is exactly the leg the proxy owns.
 */
public class TransportSecurityFilter extends OncePerRequestFilter {

    /**
     * The app serves no third-party assets, so everything can be pinned to the origin. The
     * pages do carry inline {@code <script>} blocks and inline {@code onchange} handlers, so
     * {@code 'unsafe-inline'} stays; the directive still blocks a script loaded from any
     * other host, which is what an injected {@code <script src>} would need.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    static final String HSTS = "max-age=31536000; includeSubDomains";

    private final BooleanSupplier requireHttps;

    /** Fixed-value form, kept for tests that do not exercise the dynamic switch. */
    public TransportSecurityFilter(boolean requireHttps) {
        this(() -> requireHttps);
    }

    public TransportSecurityFilter(BooleanSupplier requireHttps) {
        this.requireHttps = requireHttps;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean secure = HttpsSupport.isSecure(request);
        if (secure) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");

        // Short-circuit order is load-bearing: the supplier must not be asked for a secure
        // request, a loopback caller, or the health probe.
        if (!secure && !isLoopback(request) && !isUncredentialedLoginProbe(request)
                && requireHttps.getAsBoolean()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "HTTPS is required; configure a TLS reverse proxy and use https://");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isLoopback(HttpServletRequest request) {
        return ClientIpResolver.isLoopback(request.getRemoteAddr());
    }

    private static boolean isUncredentialedLoginProbe(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if (!request.getRequestURI().equals(request.getContextPath() + "/login")) {
            return false;
        }
        return request.getHeader("Cookie") == null;
    }
}
