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
 * <p>XFF is read right-to-left, skipping hops that are themselves trusted proxies, and the
 * first untrusted address found is returned. The leftmost entry cannot be used: nginx's
 * documented default ({@code $proxy_add_x_forwarded_for}) appends the address it saw to any
 * header the client already sent, so everything to the left of that last appended value is
 * client-controlled. Walking from the right is also correct for a proxy configured to
 * overwrite the header ({@code $remote_addr}), where the header holds exactly one entry. An
 * empty, unparseable, or entirely trusted-proxy chain falls back to the socket address.
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

    /**
     * True when the direct TCP peer is loopback or one of the configured proxies.
     *
     * <p>The header-auth filter uses this on the <em>socket</em> address, not on any forwarded
     * header: only a peer we already trust may assert somebody else's identity.
     */
    public static boolean isTrustedPeer(String remoteAddr) {
        return isTrustedProxyPeer(remoteAddr);
    }

    public static String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (isTrustedProxyPeer(peer)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                for (int i = hops.length - 1; i >= 0; i--) {
                    String hop = hops[i].trim();
                    InetAddress address = parseIpLiteral(hop);
                    if (address == null) {
                        // Not an address a proxy could have appended: client-supplied text.
                        continue;
                    }
                    if (isTrustedProxy(address)) {
                        // Another proxy of ours; the client is further left.
                        continue;
                    }
                    return hop;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                String trimmed = realIp.trim();
                if (parseIpLiteral(trimmed) != null) {
                    return trimmed;
                }
            }
        }
        return peer;
    }

    private static boolean isTrustedProxyPeer(String remoteAddr) {
        InetAddress address = parseIpLiteral(remoteAddr);
        return address != null && isTrustedProxy(address);
    }

    private static boolean isTrustedProxy(InetAddress address) {
        for (ProxyRule rule : trustedProxies) {
            if (rule.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses an IP literal without ever asking DNS. {@link InetAddress#getByName} resolves
     * hostnames, which would turn a forwarding header into an outbound lookup; the structural
     * checks below admit only syntactically valid IPv4/IPv6 forms, and a malformed one of those
     * makes the JVM reject the literal rather than resolve it.
     */
    private static InetAddress parseIpLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        // Optional IPv6 zone id ("fe80::1%eth0"): it does not affect the address bytes.
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.isEmpty() || value.length() > 45) {
            return null;
        }
        if (!looksLikeIpv4(value) && !looksLikeIpv6(value)) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean looksLikeIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpv6(String value) {
        if (value.indexOf(':') < 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') {
                return false;
            }
        }
        return true;
    }
}
