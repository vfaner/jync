package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.qqmu.jync.util.ClientIpResolver;

/**
 * HTTPS enforcement and the transport-level response headers.
 *
 * <p>The switch is off by default because most deployments sit behind a reverse proxy that
 * already terminates TLS. When it is on, the only requests let through over plain HTTP are
 * the ones that cannot leak a credential: loopback (same-host ops and probes) and an
 * unauthenticated {@code GET /login}, which is the container health probe's target and
 * carries no session cookie.
 *
 * <p>An {@code X-Forwarded-Proto} is believed only from a peer named as a trusted proxy,
 * for the same reason {@link ClientIpResolver} does not trust forwarding headers from an
 * arbitrary LAN client: otherwise every client could claim HTTPS itself.
 */
class TransportSecurityFilterTest {

    private MockFilterChain chain;

    @BeforeEach
    void useTheShippedProxyDefaults() {
        // Other suites configure the process-wide trusted set; these cases assume loopback.
        ClientIpResolver.configureTrustedProxies("");
        chain = new MockFilterChain();
    }

    @AfterEach
    void restoreTheShippedProxyDefaults() {
        ClientIpResolver.configureTrustedProxies("");
    }

    @Test
    void aPlainRequestIsServedWhenTheSwitchIsOff() throws Exception {
        MockHttpServletResponse response = run(false,
                request("POST", "/databases/save", "203.0.113.9"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aPlainRequestIsRejectedWhenHttpsIsRequired() throws Exception {
        MockHttpServletResponse response = run(true,
                request("POST", "/databases/save", "203.0.113.9"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void loopbackIsAlwaysLetThrough() throws Exception {
        assertThat(run(true, request("POST", "/databases/save", "127.0.0.1")).getStatus())
                .isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theLoginPageStaysReachableForAnUncredentialedProbe() throws Exception {
        // The container probe hits GET /login without a Cookie header; refusing it would
        // send a liveness probe into a crash loop on a hardening-enabled deployment.
        MockHttpServletResponse response = run(true, request("GET", "/login", "10.0.0.5"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theLoginPageIsRefusedOnceThereIsASessionCookieToProtect() throws Exception {
        MockHttpServletRequest request = request("GET", "/login", "10.0.0.5");
        request.setCookies(new Cookie("JSESSIONID", "abc"));

        assertThat(run(true, request).getStatus()).isEqualTo(403);
    }

    @Test
    void aLoginPostIsNeverAllowedOverPlainHttp() throws Exception {
        // The password crosses the wire here; the probe exemption is GET-only on purpose.
        assertThat(run(true, request("POST", "/login", "10.0.0.5")).getStatus()).isEqualTo(403);
    }

    @Test
    void aTrustedProxyMayAssertHttps() throws Exception {
        MockHttpServletRequest request = request("POST", "/databases/save", "127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");

        MockHttpServletResponse response = run(true, request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Strict-Transport-Security")).isNotNull();
    }

    @Test
    void anUntrustedPeerCannotAssertHttps() throws Exception {
        MockHttpServletRequest request = request("POST", "/databases/save", "203.0.113.9");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThat(run(true, request).getStatus()).isEqualTo(403);
    }

    @Test
    void anHttpsResponseCarriesHsts() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setSecure(true);

        MockHttpServletResponse response = run(false, request);

        assertThat(response.getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    void aPlainResponseCarriesNoHsts() throws Exception {
        assertThat(run(false, request("GET", "/", "203.0.113.9"))
                .getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void everyResponseCarriesTheStaticSecurityHeaders() throws Exception {
        MockHttpServletResponse response = run(false, request("GET", "/", "203.0.113.9"));

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("object-src 'none'")
                .contains("base-uri 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Permissions-Policy"))
                .contains("geolocation=()").contains("camera=()");
    }

    @Test
    void aRefusalStillCarriesTheStaticSecurityHeaders() throws Exception {
        MockHttpServletResponse response = run(true,
                request("POST", "/databases/save", "203.0.113.9"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Content-Security-Policy")).isNotNull();
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("same-origin");
    }

    @Test
    void theSwitchIsReadOnEveryRequest() throws Exception {
        // The setting is page-editable now; a filter constructed with a snapshot would keep
        // enforcing the old value until the next restart.
        AtomicBoolean requireHttps = new AtomicBoolean(false);

        assertThat(run(requireHttps::get,
                request("POST", "/databases/save", "203.0.113.9")).getStatus()).isEqualTo(200);

        requireHttps.set(true);

        assertThat(run(requireHttps::get,
                request("POST", "/databases/save", "203.0.113.9")).getStatus()).isEqualTo(403);
    }

    @Test
    void aSecureRequestNeverConsultsTheSwitch() throws Exception {
        // Reading the database on the HTTPS path would charge every TLS request for a value
        // that cannot change the outcome.
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setSecure(true);
        AtomicBoolean consulted = new AtomicBoolean(false);
        BooleanSupplier exploding = () -> {
            consulted.set(true);
            throw new IllegalStateException("must not be asked");
        };

        MockHttpServletResponse response = new MockHttpServletResponse();
        new TransportSecurityFilter(exploding).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(consulted).isFalse();
    }

    @Test
    void aConfiguredProxyIsNotLoopbackForTheExemption() throws Exception {
        // Only a same-host caller is exempt. A remote proxy relaying plaintext is exactly the
        // request enforcement exists to refuse.
        ClientIpResolver.configureTrustedProxies("10.0.0.7");

        assertThat(run(true, request("POST", "/databases/save", "10.0.0.7")).getStatus())
                .isEqualTo(403);
    }

    private MockHttpServletResponse run(boolean requireHttps, MockHttpServletRequest request)
            throws Exception {
        return run(() -> requireHttps, request);
    }

    private MockHttpServletResponse run(BooleanSupplier requireHttps,
                                        MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TransportSecurityFilter(requireHttps).doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String method, String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
