package com.qqmu.jync.service.auth;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.qqmu.jync.service.tls.TlsConfig;
import com.qqmu.jync.service.tls.TlsRuntimeState;
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

    /** Hostnames and IPv4 literals; a bare colon means IPv6 and is handled separately. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}");

    /** Enough of the IPv6 alphabet to trust; the connector itself rejects anything invalid. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final BooleanSupplier requireHttps;
    private final Supplier<TlsRuntimeState> tlsState;
    private final String redirectHost;

    /** Fixed-value form, kept for tests that do not exercise the dynamic switch. */
    public TransportSecurityFilter(boolean requireHttps) {
        this(() -> requireHttps);
    }

    public TransportSecurityFilter(BooleanSupplier requireHttps) {
        this(requireHttps, TlsRuntimeState::disabled, "");
    }

    public TransportSecurityFilter(BooleanSupplier requireHttps,
                                   Supplier<TlsRuntimeState> tlsState,
                                   String redirectHost) {
        this.requireHttps = requireHttps;
        this.tlsState = tlsState;
        this.redirectHost = redirectHost == null ? "" : redirectHost.trim();
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

        // Short-circuit order is load-bearing: the suppliers must not be asked for a secure
        // request, a loopback caller, or the health probe.
        if (secure || isLoopback(request) || isUncredentialedLoginProbe(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // The app's own HTTPS connector wins over the proxy-mode switch: when it was loaded at
        // startup there is a real TLS endpoint to send the browser to, and a 403 would hide it.
        Optional<TlsConfig> active = tlsState.get().active();
        if (active.isPresent() && active.get().redirectHttp()) {
            redirectToHttps(request, response, active.get().httpsPort());
            return;
        }

        if (requireHttps.getAsBoolean()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "HTTPS is required; configure a TLS reverse proxy and use https://");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Sends the browser to the app-terminated HTTPS port, preserving method and body.
     *
     * <p>The authority comes from the request's server name (or an explicit
     * {@code app.tls.redirect-host}), never from {@code X-Forwarded-Host}: an attacker-supplied
     * Host would otherwise turn this into an open redirect. An unsafe value is refused outright
     * rather than guessed at.
     */
    private void redirectToHttps(HttpServletRequest request, HttpServletResponse response,
                                 int httpsPort) throws IOException {
        String host = redirectHost.isEmpty() ? request.getServerName() : redirectHost;
        String authority = urlAuthority(host);
        if (authority == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "The request host cannot be used for an HTTPS redirect");
            return;
        }

        String query = request.getQueryString();
        StringBuilder location = new StringBuilder("https://").append(authority);
        if (httpsPort != 443) {
            location.append(':').append(httpsPort);
        }
        location.append(request.getRequestURI());
        if (query != null && !query.isEmpty()) {
            location.append('?').append(query);
        }

        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        response.setHeader("Location", location.toString());
    }

    /** The URL authority, or {@code null} when the value is not a plain host or IP. */
    private static String urlAuthority(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            String inner = host.substring(1, host.length() - 1);
            return isIpv6(inner) ? "[" + inner + "]" : null;
        }
        if (host.indexOf(':') >= 0) {
            // A bare IPv6 literal (Tomcat returns "::1", not "[::1]"); a "host:port" value is
            // refused because the port we add comes from the saved configuration.
            return isIpv6(host) ? "[" + host + "]" : null;
        }
        return HOST.matcher(host).matches() ? host : null;
    }

    private static boolean isIpv6(String value) {
        return IPV6.matcher(value).matches()
                && value.indexOf(':') >= 0
                && value.indexOf('%') < 0;
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
