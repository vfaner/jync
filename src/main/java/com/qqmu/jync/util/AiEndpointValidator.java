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
     * @return The resolved InetAddress, to avoid duplicate DNS lookups for SSRF protection.
     */
    public static InetAddress validateBaseUrl(String baseUrl) {
        // A blank URL has no scheme either, but URI(null) throws NPE, and callers treat
        // IllegalArgumentException as the only failure channel — an NPE would surface as a
        // 500 instead of the dialog's own "invalid URL" message.
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("error.ai.baseUrl.invalid");
        }
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
        // Check blocked hosts directly
        String trimmed = host.trim();
        if (BLOCKED_HOSTS.contains(trimmed.toUpperCase(java.util.Locale.ROOT))
                || ALIYUN_METADATA_IP.equals(trimmed)) {
            throw new IllegalArgumentException("error.ai.baseUrl.blocked");
        }
        try {
            // Every resolved address must pass: a hostname may answer with several records and
            // the HTTP client re-resolves at connect time, so one public answer must not vouch
            // for a link-local/metadata sibling (same rule as the webhook validator).
            InetAddress[] addresses = InetAddress.getAllByName(trimmed);
            for (InetAddress address : addresses) {
                if (address.isLinkLocalAddress() || isMetadataAddress(address)) {
                    throw new IllegalArgumentException("error.ai.baseUrl.blocked");
                }
            }
            return addresses[0];
        } catch (UnknownHostException e) {
            // An unresolvable host is refused, not passed through: a name that does not
            // resolve for the validator but resolves for the HTTP client's connect-time
            // lookup is exactly the DNS-rebinding shape (NXDOMAIN here, the metadata IP
            // there). The probe would fail to connect anyway, so nothing usable is lost.
            throw new IllegalArgumentException("error.ai.baseUrl.unresolved");
        }
    }

    /** AWS IPv6 metadata endpoint — unique-local, not flagged as link-local by the JDK. */
    private static final InetAddress AWS_IPV6_METADATA = metadataLiteral("fd00:ec2::254");

    private static InetAddress metadataLiteral(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);   // a literal IP never needs DNS
        }
    }

    /** Fixed cloud-metadata addresses that are never model providers, even though not link-local. */
    private static boolean isMetadataAddress(InetAddress address) {
        return address.equals(AWS_IPV6_METADATA)
                || ALIYUN_METADATA_IP.equals(address.getHostAddress());
    }

}
