package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Forwarding headers are honored only from an explicitly trusted proxy, so a client that
 * connects directly — even from the private network a Jync deployment normally lives on —
 * cannot forge its identity to dodge IP-based throttling or poison the audit trail.
 */
class ClientIpResolverTest {

    @BeforeEach
    void resetTrustedProxies() {
        ClientIpResolver.configureTrustedProxies(null);
    }

    @Test
    void ignoresForwardedHeadersFromAPublicDirectPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "10.0.0.50");
        request.addHeader("X-Real-IP", "10.0.0.51");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void ignoresForwardedHeadersFromAnUnconfiguredPrivatePeer() {
        // A client on the LAN talks to Jync directly. Treating "private peer" as "proxy"
        // let any of them rotate X-Forwarded-For and walk around the lockout.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.addHeader("X-Real-IP", "203.0.113.20");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("192.168.1.10");
    }

    @Test
    void honorsTheNearestUntrustedHopAppendedByTheProxy() {
        // nginx's documented default is $proxy_add_x_forwarded_for, which APPENDS the peer it
        // saw: "<whatever the client sent>, <real client ip>". The leftmost entry is therefore
        // client-controlled, and trusting it lets every request forge a fresh lockout key.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void ignoresAClientSuppliedPrefixWhenTheProxyAppends() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 203.0.113.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void skipsARightmostHopThatIsItselfATrustedProxy() {
        // Two proxies in the chain: the rightmost hop is the inner trusted proxy, so the
        // client is the first untrusted address to its left.
        ClientIpResolver.configureTrustedProxies("10.0.0.0/8");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void ignoresUnparseableEntriesInsteadOfReturningThem() {
        // Only the address a proxy appended can be trusted; junk on its left is the client's
        // own text and must never become the lockout key or the audited source address.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "not-an-ip, 203.0.113.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToTheSocketAddressWhenEveryHopIsATrustedProxy() {
        ClientIpResolver.configureTrustedProxies("10.0.0.0/8");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.5, 10.0.0.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void honorsForwardedHeadersFromAnExplicitlyConfiguredProxy() {
        ClientIpResolver.configureTrustedProxies("192.168.1.10");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Real-IP", "203.0.113.20");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @Test
    void honorsForwardedHeadersFromAConfiguredProxyCidr() {
        ClientIpResolver.configureTrustedProxies("10.0.0.0/8");

        MockHttpServletRequest inside = new MockHttpServletRequest();
        inside.setRemoteAddr("10.42.7.3");
        inside.addHeader("X-Forwarded-For", "203.0.113.30");
        assertThat(ClientIpResolver.resolve(inside)).isEqualTo("203.0.113.30");

        MockHttpServletRequest outside = new MockHttpServletRequest();
        outside.setRemoteAddr("11.42.7.3");
        outside.addHeader("X-Forwarded-For", "203.0.113.31");
        assertThat(ClientIpResolver.resolve(outside)).isEqualTo("11.42.7.3");
    }

    @Test
    void fallsBackToSocketAddressWhenProxyHeadersAreBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "  ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("127.0.0.1");
    }
}
