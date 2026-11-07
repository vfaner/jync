package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.qqmu.jync.service.tls.TlsConfig;
import com.qqmu.jync.service.tls.TlsKeystoreFormat;
import com.qqmu.jync.service.tls.TlsRuntimeState;
import com.qqmu.jync.util.ClientIpResolver;

/**
 * HTTPS enforcement and the transport-level response headers.
 *
 * <p>The switch is off by default because most deployments sit behind a reverse proxy that
 * already terminates TLS. When it is on, plaintext requests are redirected as soon as the
 * app's own HTTPS connector is serving — the mainstream rule, with no exception for the
 * source address. Two exemptions remain: an unauthenticated {@code GET /login} (the
 * container health probe's target, carrying no session cookie), and loopback while no HTTPS
 * endpoint exists at all — the rescue path that keeps a broken certificate from locking the
 * operator out of the page that fixes it.
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
    void loopbackIsLetThroughOnlyWhileNoHttpsEndpointExists() throws Exception {
        // The rescue path: a saved certificate that cannot start leaves no HTTPS endpoint to
        // redirect to, so the operator must still reach the page from the machine itself.
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


    @Test
    void redirectsPlaintextToTheActiveHttpsConnector() throws Exception {
        MockHttpServletRequest request = request("GET", "/projects?x=1", "203.0.113.9");
        request.setServerName("example.com");

        MockHttpServletResponse response = runWithTls(state(8443, true), false, "", request);

        assertThat(response.getStatus()).isEqualTo(307);
        assertThat(response.getHeader("Location"))
                .isEqualTo("https://example.com:8443/projects?x=1");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void omitsThePortWhenHttpsRunsOn443() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setServerName("example.com");

        assertThat(runWithTls(state(443, true), false, "", request).getHeader("Location"))
                .isEqualTo("https://example.com/");
    }

    @Test
    void redirectPreservesPostMethod() throws Exception {
        MockHttpServletRequest request = request("POST", "/system-config/tls", "203.0.113.9");
        request.setServerName("example.com");

        assertThat(runWithTls(state(8443, true), false, "", request).getStatus()).isEqualTo(307);
    }

    @Test
    void redirectWinsOverTheForbiddenSwitch() throws Exception {
        MockHttpServletRequest request = request("GET", "/projects", "203.0.113.9");
        request.setServerName("example.com");

        assertThat(runWithTls(state(8443, true), true, "", request).getStatus())
                .isEqualTo(307);
    }

    @Test
    void redirectOffAndRequireHttpsOnReturns403() throws Exception {
        MockHttpServletRequest request = request("GET", "/projects", "203.0.113.9");
        request.setServerName("example.com");

        assertThat(runWithTls(state(8443, false), true, "", request).getStatus())
                .isEqualTo(403);
    }

    @Test
    void redirectOffAndRequireHttpsOffPassesThrough() throws Exception {
        MockHttpServletRequest request = request("GET", "/projects", "203.0.113.9");
        request.setServerName("example.com");

        assertThat(runWithTls(state(8443, false), false, "", request).getStatus())
                .isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void loopbackIsRedirectedOnceAnHttpsConnectorIsActive() throws Exception {
        // Once there is a real HTTPS endpoint, the source address stops mattering: this is
        // what every mainstream stack does, and it is what makes the switch mean one thing.
        MockHttpServletRequest request = request("GET", "/projects?x=1", "127.0.0.1");
        request.setServerName("localhost");

        MockHttpServletResponse response = runWithTls(state(8443, true), false, "", request);

        assertThat(response.getStatus()).isEqualTo(307);
        assertThat(response.getHeader("Location")).isEqualTo("https://localhost:8443/projects?x=1");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void loopbackIsRefusedWhenAnHttpsEndpointExistsButRedirectIsOff() throws Exception {
        // No parallel exemption in the 403 path either: the certificate is serving, so a
        // browser has somewhere to go; silence here would only hide a proxy misconfiguration.
        MockHttpServletRequest request = request("GET", "/projects", "127.0.0.1");
        request.setServerName("localhost");

        assertThat(runWithTls(state(8443, false), true, "", request).getStatus())
                .isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theUncredentialedLoginProbeIsNeverRedirected() throws Exception {
        MockHttpServletRequest request = request("GET", "/login", "10.0.0.5");
        request.setServerName("example.com");

        assertThat(runWithTls(state(8443, true), false, "", request).getStatus())
                .isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void secureRequestsAreNeverRedirectedAndDoNotConsultTheSuppliers() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setSecure(true);
        BooleanSupplier explodingSwitch = () -> {
            throw new IllegalStateException("must not be asked");
        };
        Supplier<TlsRuntimeState> explodingState = () -> {
            throw new IllegalStateException("must not be asked");
        };

        MockHttpServletResponse response = new MockHttpServletResponse();
        new TransportSecurityFilter(explodingSwitch, explodingState, "").doFilter(request, response,
                chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Strict-Transport-Security")).isNotNull();
    }

    @Test
    void rejectsAnUnsafeHostInsteadOfRedirecting() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setServerName("evil.example/path");

        MockHttpServletResponse response = runWithTls(state(8443, true), false, "", request);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader("Location")).isNull();
    }

    @Test
    void configuredRedirectHostOverridesTheRequestHost() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setServerName("10.0.0.5");

        assertThat(runWithTls(state(8443, true), false, "jync.example.com", request)
                .getHeader("Location")).isEqualTo("https://jync.example.com:8443/");
    }

    @Test
    void ipv6ServerNameIsBracketed() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setServerName("::1");

        assertThat(runWithTls(state(8443, true), false, "", request).getHeader("Location"))
                .isEqualTo("https://[::1]:8443/");
    }

    @Test
    void rejectsAnIpv6ZoneId() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setServerName("fe80::1%en0");

        assertThat(runWithTls(state(8443, true), false, "", request).getStatus())
                .isEqualTo(400);
    }

    @Test
    void ignoresXForwardedHost() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "203.0.113.9");
        request.setServerName("example.com");
        request.addHeader("X-Forwarded-Host", "evil.example");

        assertThat(runWithTls(state(8443, true), false, "", request).getHeader("Location"))
                .isEqualTo("https://example.com:8443/");
    }

    @Test
    void aDisabledOrCorruptTlsStateNeverRedirects() throws Exception {
        MockHttpServletRequest request = request("GET", "/projects", "203.0.113.9");
        request.setServerName("example.com");

        assertThat(runWithTls(TlsRuntimeState.disabled(), false, "", request).getStatus())
                .isEqualTo(200);
        // A MockFilterChain may be invoked once per request; this test sends two.
        chain = new MockFilterChain();

        TlsRuntimeState corrupt = org.mockito.Mockito.mock(TlsRuntimeState.class);
        org.mockito.Mockito.when(corrupt.active()).thenReturn(Optional.empty());
        org.mockito.Mockito.when(corrupt.problemKey()).thenReturn("error.tls.storeCorrupt");
        assertThat(runWithTls(corrupt, false, "", request).getStatus()).isEqualTo(200);
    }

    private static TlsRuntimeState state(int httpsPort, boolean redirectHttp) {
        TlsRuntimeState state = org.mockito.Mockito.mock(TlsRuntimeState.class);
        org.mockito.Mockito.when(state.active()).thenReturn(Optional.of(new TlsConfig(
                TlsKeystoreFormat.PKCS12, "keystore-x.p12", "changeit", httpsPort,
                redirectHttp, Instant.now(), "00")));
        return state;
    }

    private MockHttpServletResponse runWithTls(TlsRuntimeState state, boolean requireHttps,
                                               String redirectHost,
                                               MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TransportSecurityFilter(() -> requireHttps, () -> state, redirectHost)
                .doFilter(request, response, chain);
        return response;
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
