package com.qqmu.jync.service.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.qqmu.jync.service.tls.TlsConfig;
import com.qqmu.jync.service.tls.TlsRuntimeState;
import com.qqmu.jync.util.ClientIpResolver;
import com.qqmu.jync.util.HttpsSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * Transport security: HTTPS enforcement and the headers that only make sense with it.
 *
 * <p>The switch exists because most deployments sit behind a reverse proxy that already
 * terminates TLS; turning enforcement on there without telling the app which peers are
 * proxies would reject everything. When the app's own HTTPS connector is serving, every
 * plaintext request is redirected to it, whatever its source address — the mainstream rule.
 * Otherwise an enforced request is answered 403, with two exemptions:
 *
 * <ul>
 *   <li><strong>Loopback, while no HTTPS endpoint exists.</strong> This is the rescue path:
 *       a saved certificate that cannot start must not also lock the operator out of the
 *       page that replaces or removes it. Once a connector is serving there is somewhere to
 *       send the request, so loopback stops being exempt. This is loopback proper, not the
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
 * next restart. HTTPS traffic skips it entirely. A plaintext request needs the TLS snapshot
 * for the CSP as well, because the login form must be allowed to follow the redirect to the
 * app's own HTTPS endpoint; that snapshot is an in-memory value, not a database lookup.
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
@Slf4j
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

    /** A bare http(s) origin: no credentials, path, query or fragment. */
    private static final Pattern ORIGIN = Pattern.compile(
            "(?i)^(https?)://(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9._-]{1,253})(?::(\\d{1,5}))?$");

    private final BooleanSupplier requireHttps;
    private final Supplier<TlsRuntimeState> tlsState;
    private final String redirectHost;
    private final List<String> formActionOrigins;

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
        this(requireHttps, tlsState, redirectHost, "");
    }

    public TransportSecurityFilter(BooleanSupplier requireHttps,
                                   Supplier<TlsRuntimeState> tlsState,
                                   String redirectHost,
                                   String formActionOrigins) {
        this.requireHttps = requireHttps;
        this.tlsState = tlsState;
        this.redirectHost = redirectHost == null ? "" : redirectHost.trim();
        this.formActionOrigins = parseOrigins(formActionOrigins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean secure = HttpsSupport.isSecure(request);
        if (secure) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        // The TLS snapshot is also needed to decide whether the login form's POST is allowed
        // to follow the plaintext-to-HTTPS redirect. It is a plain in-memory read, and the
        // secure path still skips it entirely.
        Optional<TlsConfig> active = secure ? Optional.empty() : tlsState.get().active();
        response.setHeader("Content-Security-Policy", contentSecurityPolicy(request, active));
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");

        if (secure || isUncredentialedLoginProbe(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // The app's own HTTPS connector wins over the proxy-mode switch: when it was loaded at
        // startup there is a real TLS endpoint to send the browser to, and a 403 would hide it.
        // The source address does not matter here — loopback gets redirected like everyone else.
        if (active.isPresent() && active.get().redirectHttp()) {
            redirectToHttps(request, response, active.get().httpsPort());
            return;
        }

        if (requireHttps.getAsBoolean()) {
            // Without a connector there is nowhere to redirect to. Loopback stays open as the
            // rescue path; with a connector serving, even loopback is refused, so the switch
            // means the same thing regardless of where the request came from.
            if (active.isEmpty() && isLoopback(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "HTTPS is required; configure a TLS reverse proxy and use https://");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * The base policy, plus any origin the login form must be allowed to reach.
     *
     * <p>A plaintext page is served on {@code http://host:8080}, and the login POST is then
     * answered with a 307 to {@code https://host:8443/login}. CSP's {@code 'self'} compares
     * schemes, so the browser blocks that redirect before the password is sent and the login
     * button looks dead. The app's own HTTPS endpoint is a safe, exact exception; a reverse
     * proxy that redirects to a different name can add its origin through
     * {@code app.security.form-action-origins}.
     */
    private String contentSecurityPolicy(HttpServletRequest request,
                                         Optional<TlsConfig> active) {
        LinkedHashSet<String> origins = new LinkedHashSet<>(formActionOrigins);
        if (active.isPresent() && active.get().redirectHttp()) {
            String authority = httpsAuthority(request, active.get().httpsPort());
            if (authority != null) {
                origins.add("https://" + authority);
            }
        }
        if (origins.isEmpty()) {
            return CONTENT_SECURITY_POLICY;
        }
        return CONTENT_SECURITY_POLICY.replace("form-action 'self'",
                "form-action 'self' " + String.join(" ", origins));
    }

    /**
     * Sends the browser to the app-terminated HTTPS port, preserving method and body.
     *
     * <p>The authority never comes from a request header. {@code getServerName()} is derived
     * from {@code Host}, so a domain that resolves to this server — and then to the attacker
     * once the scheme changes — would turn the 307 into a hop to an attacker-controlled HTTPS
     * site; {@code X-Forwarded-Host} is equally untrusted. Only two sources are safe: the
     * explicitly configured {@code app.tls.redirect-host}, or the local address of the
     * connection itself, which is the address the client actually dialled. Deployments reached
     * by a name behind NAT must set the configured host; an unsafe value is refused outright
     * rather than guessed at.
     */
    private void redirectToHttps(HttpServletRequest request, HttpServletResponse response,
                                 int httpsPort) throws IOException {
        String authority = httpsAuthority(request, httpsPort);
        if (authority == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "The request host cannot be used for an HTTPS redirect");
            return;
        }

        String query = request.getQueryString();
        StringBuilder location = new StringBuilder("https://").append(authority);
        location.append(request.getRequestURI());
        if (query != null && !query.isEmpty()) {
            location.append('?').append(query);
        }

        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        response.setHeader("Location", location.toString());
    }

    /** The redirect origin's authority, including the port unless it is the HTTPS default. */
    private String httpsAuthority(HttpServletRequest request, int httpsPort) {
        String host = redirectHost.isEmpty() ? localAuthority(request) : redirectHost;
        String authority = urlAuthority(host);
        if (authority == null) {
            return null;
        }
        return httpsPort == 443 ? authority : authority + ":" + httpsPort;
    }

    /**
     * Parses a comma/space-separated list of bare http(s) origins. Anything with a path,
     * credentials, wildcard or malformed port is ignored: a typo must not silently widen the
     * directive, and the app's normal same-origin form does not need any entry.
     */
    private static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> origins = new ArrayList<>();
        for (String token : raw.split("[,\\s]+")) {
            String candidate = token.trim();
            while (candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            Matcher matcher = ORIGIN.matcher(candidate);
            if (!matcher.matches()) {
                log.warn("Ignoring an invalid form-action origin '{}'", token);
                continue;
            }
            String portText = matcher.group(3);
            if (portText != null) {
                int port;
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    log.warn("Ignoring an invalid form-action origin '{}'", token);
                    continue;
                }
                if (port < 1 || port > 65535) {
                    log.warn("Ignoring an invalid form-action origin '{}'", token);
                    continue;
                }
            }
            origins.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT) + "://"
                    + matcher.group(2).toLowerCase(java.util.Locale.ROOT)
                    + (portText == null ? "" : ":" + portText));
        }
        return List.copyOf(origins);
    }

    /** The address the client dialled; server-controlled, unlike {@code Host}. */
    private static String localAuthority(HttpServletRequest request) {
        String address = request.getLocalAddr();
        return address == null || address.isBlank() ? request.getLocalName() : address;
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
