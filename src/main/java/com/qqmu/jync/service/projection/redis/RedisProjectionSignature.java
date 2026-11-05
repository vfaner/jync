package com.qqmu.jync.service.projection.redis;

import com.qqmu.jync.dto.RedisProjectionConfig;

/**
 * The key-space contract of a cache projection: the value format plus the key prefix.
 *
 * <p>Recorded on the CDC position row when a baseline completes, so start and rebuild can tell
 * whether the keys already in Redis were written with the contract the project now holds.
 * Everything else in {@link RedisProjectionConfig} (TTL, excluded columns, size limits) changes
 * only what is written from now on and never conflicts with existing keys, so it stays out of
 * the signature; the two fields here are the ones whose change turns the next write into a
 * {@code WRONGTYPE} error or leaves a whole old namespace behind.
 *
 * <p>The plain text form is deliberate: a rebuild that sweeps also has to delete the OLD prefix,
 * which is only recoverable if the stored signature carries it.
 */
public final class RedisProjectionSignature {

    private static final char SEPARATOR = '|';

    private RedisProjectionSignature() {
    }

    /** The signature of {@code projection}, or null when there is no contract to record. */
    public static String of(RedisProjectionConfig projection) {
        if (projection == null || projection.getKeyPrefix() == null
                || projection.getKeyPrefix().isBlank()) {
            return null;
        }
        RedisValueFormat format = projection.getValueFormat() == null
                ? RedisValueFormat.JSON
                : projection.getValueFormat();
        return format.name() + SEPARATOR + projection.getKeyPrefix();
    }

    /** The prefix encoded in a stored signature, or null when it cannot be read. */
    public static String prefixOf(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        int at = signature.indexOf(SEPARATOR);
        if (at < 0 || at == signature.length() - 1) {
            return null;
        }
        String prefix = signature.substring(at + 1);
        return prefix.isBlank() ? null : prefix;
    }
}
