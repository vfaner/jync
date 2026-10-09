package com.qqmu.jync.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.servlet.http.HttpServletRequest;

/**
 * Extracts the caller's IP, honoring the first {@code X-Forwarded-For} hop only when the direct
 * TCP peer is a trusted proxy position (loopback or a private network address, where nginx and
 * its kind typically run).
 *
 * <p>Trusting those headers unconditionally would let any direct client pick an arbitrary IP per
 * request and walk straight around IP-based login throttling. Only the first XFF value is used
 * (the client-supplied one; later hops are appended by proxies). An empty or unparseable value
 * falls back to the socket address.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
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
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
