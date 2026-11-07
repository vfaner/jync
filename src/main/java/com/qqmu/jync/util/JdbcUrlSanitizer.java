package com.qqmu.jync.util;

/**
 * Renders a JDBC URL without credentials.
 *
 * <p>The configured metadata URL, and any candidate URL an administrator types into the test
 * box, can carry a password three ways: userinfo ({@code //user:pass@host}), a
 * {@code password=} parameter, or the Oracle thin form {@code jdbc:oracle:thin:user/pass@host}.
 * All three end up in a browser or an audit row, so all three are masked here. Host and database
 * name survive — they are what makes a diagnostic useful.
 */
public final class JdbcUrlSanitizer {

    private JdbcUrlSanitizer() {
    }

    public static String sanitize(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        // Greedy to the LAST '@' before the path or query: a password may itself contain an
        // unencoded '@', and stopping at the first one would leave the tail of the password
        // in the rendered URL.
        String cleaned = url.replaceFirst("//[^/?]*@", "//");
        // Oracle thin: user/password immediately after the scheme, before @host. A raw '@'
        // in the password means the host starts after the last one.
        cleaned = cleaned.replaceFirst("(?i)^(jdbc:oracle:thin:)([^/]+)/.*@(.*)$", "$1$2/***@$3");
        cleaned = cleaned.replaceAll("(?i)([?&](?:password|user|username)=)[^&]*", "$1***");
        return cleaned;
    }
}
