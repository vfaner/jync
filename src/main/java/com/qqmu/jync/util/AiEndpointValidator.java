package com.qqmu.jync.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Guards the admin-configured AI base URL.
 *
 * <p>The URL is attacker-influenced input (anyone who reaches an ADMIN form can set it) and the
 * app will happily POST there, so it is validated in two places — when saved, and again right
 * before the request is built. The rules:
 * <ul>
 *   <li>only {@code http}/{@code https} — no {@code file:}, {@code gopher:} or jar tricks;
 *   <li>a host must exist, and no userinfo may ride along in the URL (the key lives in a header,
 *       never in a URL that gets logged);
 *   <li>link-local/cloud-metadata endpoints are refused: they are never a chat-model endpoint,
 *       and they are the classic SSRF target. Loopback and private addresses stay allowed
 *       because Ollama, LM Studio and intranet inference servers live there.
 * </ul>
 */
public final class AiEndpointValidator {

    /** Cloud metadata hostnames that are never model providers. */
    private static final java.util.Set<String> BLOCKED_HOSTS = java.util.Set.of(
            "METADATA.GOOGLE.INTERNAL", "METADATA.GOOG", "METADATA.GOOGLEAPIS.COM");

    /** Alibaba Cloud metadata service — a fixed literal address, not flagged as link-local. */
    private static final String ALIYUN_METADATA_IP = "100.100.100.200";

    private AiEndpointValidator() {
    }

    /**
     * Validates a base URL, throwing {@link IllegalArgumentException} with an i18n message key.
     */
    public static void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("error.ai.baseUrl.invalid");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("error.ai.baseUrl.scheme");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("error.ai.baseUrl.userinfo");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("error.ai.baseUrl.invalid");
        }
        if (isBlockedHost(host)) {
            throw new IllegalArgumentException("error.ai.baseUrl.blocked");
        }
    }

    /** Non-throwing check used at request time. */
    public static boolean isPermitted(String baseUrl) {
        try {
            validateBaseUrl(baseUrl);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlockedHost(String host) {
        String trimmed = host.trim();
        if (BLOCKED_HOSTS.contains(trimmed.toUpperCase()) || ALIYUN_METADATA_IP.equals(trimmed)) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(trimmed);
            return address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            // Unresolvable host: harmless for SSRF; the HTTP call later reports the DNS failure.
            return false;
        }
    }
}
