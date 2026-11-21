package com.qqmu.jync.service.alert;

import java.net.InetAddress;

/**
 * Where an alert webhook may point. Shared by the save-time validation and the send-time
 * re-check: a URL that was public when it was saved can answer with a private or metadata
 * address hours later (DNS change or rebinding), and the allow-private toggle is frozen at
 * save time — the same class, so the two checks can never drift apart.
 */
final class WebhookAddressPolicy {

    private WebhookAddressPolicy() {
    }

    static void check(InetAddress[] addresses, boolean allowPrivate) {
        for (InetAddress address : addresses) {
            if (isNeverAPublicWebhookTarget(address)) {
                throw new IllegalArgumentException("error.alert.url.invalid");
            }
            if (!allowPrivate && isPrivateOrReserved(address)) {
                throw new IllegalArgumentException("error.alert.url.private");
            }
        }
    }

    /**
     * Addresses with no public meaning at all: "any address", multicast, link-local (which
     * includes the cloud metadata endpoints 169.254.169.254 and 100.100.100.200, the latter
     * reported by Java as neither link-local nor site-local and therefore named explicitly).
     * Refused even when private targets are allowed.
     */
    private static boolean isNeverAPublicWebhookTarget(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()
                || "100.100.100.200".equals(address.getHostAddress());
    }

    /**
     * Loopback, RFC 1918, IPv6 unique-local (fc00::/7), carrier-grade NAT, benchmarking and
     * the reserved 240/4 block. Java reports IPv4-mapped literals ({@code ::ffff:10.1.2.3})
     * as {@link java.net.Inet4Address}, so the byte checks below cover both spellings.
     */
    private static boolean isPrivateOrReserved(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 0                                   // 0.0.0.0/8 "this network"
                || (first == 100 && (second & 0xC0) == 64)  // 100.64/10 carrier-grade NAT
                || (first == 198 && (second == 18 || second == 19)) // 198.18/15 benchmarking
                || first >= 240;                            // 240/4 reserved
    }
}
