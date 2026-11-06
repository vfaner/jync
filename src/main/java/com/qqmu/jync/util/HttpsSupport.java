package com.qqmu.jync.util;

import javax.servlet.http.HttpServletRequest;

/**
 * Whether the client's leg of this request is encrypted.
 *
 * <p>{@code request.isSecure()} answers it when the container itself terminates TLS. Behind
 * a reverse proxy the socket is plain HTTP and the fact lives in
 * {@code X-Forwarded-Proto} — which is believed only from a peer already named as a trusted
 * proxy, the same rule {@link ClientIpResolver} applies to {@code X-Forwarded-For}. An
 * unchecked header would let every direct LAN client claim HTTPS for a plain request.
 */
public final class HttpsSupport {

    private HttpsSupport() {
    }

    public static boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        if (!ClientIpResolver.isTrustedPeer(request.getRemoteAddr())) {
            return false;
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null) {
            return false;
        }
        // A proxy chain appends hops; the first value is the scheme the client used.
        int comma = proto.indexOf(',');
        String first = (comma >= 0 ? proto.substring(0, comma) : proto).trim();
        return "https".equalsIgnoreCase(first);
    }
}
