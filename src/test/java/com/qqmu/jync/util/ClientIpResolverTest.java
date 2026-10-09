package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * P1: forwarding headers are only honored from a loopback/private peer, so a direct client
 * cannot forge its identity to dodge IP-based throttling.
 */
class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeadersFromAPublicDirectPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "10.0.0.50");
        request.addHeader("X-Real-IP", "10.0.0.51");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void honorsForwardedForFromLoopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void honorsRealIpFromPrivatePeerWhenForwardedForIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Real-IP", "203.0.113.20");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @Test
    void fallsBackToSocketAddressWhenProxyHeadersAreBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "  ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("127.0.0.1");
    }
}
