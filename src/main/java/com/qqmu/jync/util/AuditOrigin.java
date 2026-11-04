package com.qqmu.jync.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.servlet.http.HttpServletRequest;

/**
 * Human-facing name of where a sign-in came from, for the audit trail.
 *
 * <p>This is deliberately separate from {@link ClientIpResolver}: the resolver is a security
 * control (lockout keys, spoof-proof XFF handling) and must always report the real client IP,
 * while the audit reader wants to know how the login looked in the browser. A localhost tab
 * reports {@code 0:0:0:0:0:0:0:1} as the TCP peer — the full spelling of IPv6 loopback — which
 * reads as noise, so every loopback spelling collapses to {@link #LOCAL}. Anything else is the
 * host the browser actually used, taken from the Host header: a deployment domain behind a
 * reverse proxy ({@code jync.qqmu.com}), or the server IP typed into the address bar.
 *
 * <p>This value is display-only; it never feeds throttling or authentication.
 */
public final class AuditOrigin {

    /** Sentinel the detail renderer replaces with the localized "this machine" label. */
    public static final String LOCAL = "@local";

    private AuditOrigin() {
    }

    public static String of(HttpServletRequest request) {
        String host = request.getServerName();
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            String peer = request.getRemoteAddr();
            return isLoopbackLiteral(peer) ? LOCAL : peer;
        }
        if ("localhost".equalsIgnoreCase(host) || isLoopbackLiteral(host)) {
            return LOCAL;
        }
        return host;
    }

    /**
     * True for IPv4/IPv6 loopback literals only. The shape is checked before
     * {@link InetAddress#getByName} so a hostname cannot trigger a DNS lookup.
     */
    private static boolean isLoopbackLiteral(String value) {
        if (value == null || value.isBlank() || !looksLikeIpLiteral(value.trim())) {
            return false;
        }
        try {
            return InetAddress.getByName(value.trim()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean looksLikeIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.matches("\\d{1,3}")) {
                return false;
            }
        }
        return true;
    }
}
