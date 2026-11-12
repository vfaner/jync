package com.qqmu.jync.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content digests for values that must be compared without comparing the value itself.
 *
 * <p>Used by the change log: {@code details} is a {@code @Lob}, and a metadata store may not
 * be able to compare it at all — PostgreSQL stores an unbounded {@code @Lob String} as
 * {@code oid}, where {@code = ?} binds a fresh large object and {@code like} does not exist.
 * A SHA-256 over the text is a plain {@code varchar}, so the same predicate runs everywhere.
 */
public final class DigestUtil {

    private DigestUtil() {
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes; never null. */
    public static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        return HexFormat.of().formatHex(
                digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
