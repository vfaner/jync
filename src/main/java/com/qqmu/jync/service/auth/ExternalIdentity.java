package com.qqmu.jync.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.util.CryptoUtil;

/** Parsing and comparison shared by the header filter, the ticket entry and the admin probe. */
public final class ExternalIdentity {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final int MAX_USERNAME_LENGTH = 64;

    private ExternalIdentity() {
    }

    /** Strips control characters, trims, and caps the name; blank means "no identity". */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = CONTROL.matcher(raw).replaceAll("").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_USERNAME_LENGTH
                ? cleaned.substring(0, MAX_USERNAME_LENGTH) : cleaned;
    }

    public static UserRole role(OaAuthConfig config, String username, boolean responseAdmin) {
        return ExternalUserRoles.resolve(config, username, responseAdmin);
    }

    /** Exact, trimmed match against a comma-separated value list. */
    public static boolean matches(String actual, String commaSeparatedValues) {
        if (actual == null || commaSeparatedValues == null) {
            return false;
        }
        String trimmed = actual.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (String accepted : commaSeparatedValues.split(",")) {
            if (accepted.trim().equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when no shared secret is configured, or when the presented value equals the decrypted
     * one. An undecryptable stored value (key rotated without re-entering) fails closed.
     */
    public static boolean secretMatches(OaAuthConfig config, String presented, CryptoUtil crypto) {
        if (config.getSecretHeaderName() == null) {
            return true;
        }
        if (presented == null) {
            return false;
        }
        String expected;
        try {
            expected = crypto.decrypt(config.getSecretHeaderValue());
        } catch (IllegalStateException ex) {
            return false;
        }
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
