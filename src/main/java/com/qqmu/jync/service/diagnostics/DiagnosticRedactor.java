package com.qqmu.jync.service.diagnostics;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Last line of defence for the diagnostics bundle: replaces anything that still looks like a
 * credential before it reaches an entry.
 *
 * <p>This is not the primary protection. {@link DiagnosticBundleService} builds every entry
 * field by field from a whitelist, so a password never gets the chance to arrive here. The
 * redactor covers the free text that cannot be projected — log lines, change-log details and
 * audit details written by other parts of the system — where a driver or a user may have put
 * a URL or a token in an error message.
 *
 * <p>Pattern order matters: a bearer token is masked before the assignment rule could treat
 * the word {@code Bearer} as the value and leave the token behind. Already-masked values are
 * left alone, which makes the transform idempotent.
 */
@Component
public class DiagnosticRedactor {

    static final String MARKER = "<redacted>";
    static final String PRIVATE_KEY_MARKER = "[redacted private key]";
    static final int MAX_LINE_LENGTH = 4000;

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

    /** {@code scheme://user:password@host} — keep the user, drop the password. */
    private static final Pattern URL_USERINFO = Pattern.compile(
            "(://[^\\s:/@]+:)[^\\s/@]+@");

    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&](?:password|passwd|pwd|secret|token|api[_-]?key|access_token"
                    + "|authorization)=)[^&\\s]+");

    private static final Pattern ENCRYPTED = Pattern.compile(
            "(?i)\\benc:[A-Za-z0-9+/=_-]+");

    /**
     * The whole assignment disappears, keyword included. Keeping the keyword would leak it
     * when the keyword itself is the secret-shaped word ({@code credential=cred}), and a
     * diagnostics file does not need the parameter name to be useful.
     */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access_token"
                    + "|authorization|credential)\\b(\\s*[=:]\\s*)(?!<redacted>)\\S+");

    public String redact(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String value = ASSIGNMENT.matcher(
                        ENCRYPTED.matcher(
                                SENSITIVE_QUERY.matcher(
                                        URL_USERINFO.matcher(
                                                BEARER.matcher(
                                                        PRIVATE_KEY.matcher(input)
                                                                .replaceAll(PRIVATE_KEY_MARKER))
                                                        .replaceAll("Bearer " + MARKER))
                                                .replaceAll("$1" + MARKER + "@"))
                                        .replaceAll("$1" + MARKER))
                                .replaceAll("enc:" + MARKER))
                .replaceAll(MARKER);
        return truncateLines(value);
    }

    private static String truncateLines(String value) {
        if (value.length() <= MAX_LINE_LENGTH && value.indexOf('\n') < 0) {
            return value;
        }
        String[] lines = value.split("\n", -1);
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            out.append(line, 0, Math.min(line.length(), MAX_LINE_LENGTH));
        }
        return out.toString();
    }
}
