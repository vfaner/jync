package com.qqmu.jync.service.projection.redis;

import java.util.List;

import com.qqmu.jync.model.RedisTarget;

/**
 * Deletes every key a project owns, addressed by its projection prefix.
 *
 * <p>The handle below is the whole vocabulary: SCAN and UNLINK. There is deliberately no
 * command that could remove keys by pattern in one step — a cache shared with other projects
 * (and, on a cluster, with keys living on other masters) must never be widened past the
 * prefix, and {@code FLUSHDB}/{@code FLUSHALL} would take the whole database with it.
 */
public interface RedisPrefixSweeper {

    /**
     * @param prefix the project's key prefix; a missing trailing ':' is added so the pattern
     *               cannot spill into a longer numeric prefix ({@code jync:1} vs {@code jync:10})
     * @return the number of keys the server reported as unlinked; a re-SCAN may report the
     *         same key twice, so this is an upper bound on distinct keys removed
     */
    long sweep(RedisTarget target, String prefix);

    /** One SCAN page; {@code cursor "0"} means the iteration is complete. */
    record ScanPage(String cursor, List<String> keys) {
        public ScanPage {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }
    }

    /** One connection (one master, on a cluster) and the only two commands a sweep may use. */
    interface Handle extends AutoCloseable {

        ScanPage scan(String pattern);

        long unlink(List<String> keys);

        @Override
        void close();
    }

    /** Opens one handle per master: one for a standalone server, one per shard on a cluster. */
    @FunctionalInterface
    interface Connector {
        List<Handle> open(RedisTarget target);
    }
}
