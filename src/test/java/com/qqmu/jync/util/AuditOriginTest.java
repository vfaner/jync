package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;

/**
 * How the audit trail names the login origin.
 *
 * <p>{@code 0:0:0:0:0:0:0:1} is the IPv6 loopback (what the JVM reports for a localhost
 * browser tab); it and every other loopback spelling collapse to the local sentinel. A
 * domain in the Host header is kept verbatim, which is what the address bar shows for a
 * proxied deployment.
 */
class AuditOriginTest {

    private HttpServletRequest request(String host, String peer) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServerName()).thenReturn(host);
        when(request.getRemoteAddr()).thenReturn(peer);
        return request;
    }

    @Test
    void loopbackSpellingsCollapseToTheLocalSentinel() {
        assertThat(AuditOrigin.of(request("localhost", "127.0.0.1")))
                .isEqualTo(AuditOrigin.LOCAL);
        assertThat(AuditOrigin.of(request("127.0.0.1", "127.0.0.1")))
                .isEqualTo(AuditOrigin.LOCAL);
        assertThat(AuditOrigin.of(request("0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1")))
                .isEqualTo(AuditOrigin.LOCAL);
        assertThat(AuditOrigin.of(request("::1", "0:0:0:0:0:0:0:1")))
                .isEqualTo(AuditOrigin.LOCAL);
    }

    @Test
    void aDomainHostIsKeptVerbatim() {
        assertThat(AuditOrigin.of(request("jync.qqmu.com", "127.0.0.1")))
                .isEqualTo("jync.qqmu.com");
    }

    @Test
    void anIpHostIsKeptAndBlankHostFallsBackToTheSocketPeer() {
        assertThat(AuditOrigin.of(request("192.168.1.10", "192.168.1.20")))
                .isEqualTo("192.168.1.10");
        HttpServletRequest noHost = mock(HttpServletRequest.class);
        when(noHost.getServerName()).thenReturn("");
        when(noHost.getRemoteAddr()).thenReturn("10.0.0.7");
        assertThat(AuditOrigin.of(noHost)).isEqualTo("10.0.0.7");
    }
}
