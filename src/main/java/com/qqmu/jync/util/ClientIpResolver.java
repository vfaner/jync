package com.qqmu.jync.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

/**
 * Extracts the caller's IP, honoring {@code X-Forwarded-For}/{@code X-Real-IP} only when the
 * direct TCP peer is an explicitly configured reverse proxy.
 *
 * <p>Trusting forwarding headers from every private address — the obvious shortcut, and what
 * this class used to do — is wrong in exactly the deployment Jync sees: clients on the LAN
 * connect directly and are private addresses themselves. Each of them could then send a fresh
 * {@code X-Forwarded-For} per request, giving every guess a different lockout key (unlimited
 * password guessing) and writing a forged address into the audit trail. The trusted set is
 * therefore a configuration list that defaults to loopback only; a proxy on another host must
 * be named in {@code app.security.trusted-proxies}.
 *
 * <p>Only the first XFF value is used (the client-supplied one; later hops are appended by
 * proxies). An empty or unparseable value falls back to the socket address.
 */
public final class ClientIpResolver {

    /** Exact address or IPv4/IPv6 CIDR; both reduce to "first N bits must match". */
    private record ProxyRule(byte[] address, int prefixBits) {

        static ProxyRule parse(String text) {
            String value = text.trim();
            int slash = value.indexOf('/');
            try {
                if (slash > 0) {
                    InetAddress base = InetAddress.getByName(value.substring(0, slash).trim());
                    int bits = Integer.parseInt(value.substring(slash + 1).trim());
                    if (bits < 0 || bits > base.getAddress().length * 8) {
                        throw new IllegalArgumentException("prefix out of range: " + value);
                    }
                    return new ProxyRule(base.getAddress(), bits);
                }
                InetAddress exact = InetAddress.getByName(value);
                return new ProxyRule(exact.getAddress(), exact.getAddress().length * 8);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Not a usable proxy address: '" + text + "'", e);
            }
        }

        boolean matches(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (other.length != address.length) {
                return false;
            }
            int wholeBytes = prefixBits / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (other[i] != address[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainder);
            return (other[wholeBytes] & mask) == (address[wholeBytes] & mask);
        }
    }

    /** Loopback is always trusted: a same-host nginx is the common case and cannot be spoofed. */
    private static final List<ProxyRule> DEFAULT_PROXIES = List.of(
            ProxyRule.parse("127.0.0.1"), ProxyRule.parse("::1"));

    private static volatile List<ProxyRule> trustedProxies = DEFAULT_PROXIES;

    private ClientIpResolver() {
    }

    /**
     * Replaces the trusted-proxy list with the built-in loopback entries plus the configured
     * ones. Called once at startup; {@code null} or blank restores the default.
     *
     * @param spec comma-separated addresses or CIDRs ({@code 10.0.0.0/8})
     * @throws IllegalArgumentException when an entry is not an address or CIDR — a typo must
     *     fail the boot rather than silently leave the proxy untrusted
     */
    public static void configureTrustedProxies(String spec) {
        if (spec == null || spec.isBlank()) {
            trustedProxies = DEFAULT_PROXIES;
            return;
        }
        List<ProxyRule> rules = new ArrayList<>(DEFAULT_PROXIES);
        for (String part : spec.split(",")) {
            if (!part.isBlank()) {
                rules.add(ProxyRule.parse(part));
            }
        }
        trustedProxies = List.copyOf(rules);
    }

    public static String resolve(HttpServletRequest request) {
        if (isTrustedProxyPeer(request.getRemoteAddr())) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean isTrustedProxyPeer(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            for (ProxyRule rule : trustedProxies) {
                if (rule.matches(addr)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
