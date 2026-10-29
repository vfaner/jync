package com.qqmu.jync.service.task;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;

/**
 * How many full-load workers the connection pools can actually seat.
 *
 * <p>Shared by the engine, which clamps the configured value, and the project page, which
 * shows the resulting number — the field's "实际值受连接池上限约束" says a cap exists but not
 * what it is, so without the number a saved 8 looks like it runs as 8. One rule in one place
 * keeps the page and the engine from disagreeing about what the cap is.
 */
public final class FullLoadCapacity {

    /** Hard ceiling on full-load parallelism, matching the capacity plan. */
    public static final int MAX_PARALLELISM = 8;

    private FullLoadCapacity() {
    }

    /**
     * Workers the pool can seat next to the main cycle's connections.
     *
     * <p>Distinct endpoints give each side its own pool: the main cycle holds one connection
     * per side for the whole load, so a pool of {@code P} seats {@code P-1} workers. When both
     * sides point at the same {@link DatabaseConfig} they share one pool, the main cycle holds
     * two connections, and every worker borrows one of each — {@code (P-2)/2} workers.
     */
    public static int poolBound(SyncProperties properties, DatabaseConfig source,
                                DatabaseConfig target) {
        int pool = Math.max(2, properties.getMaxPoolSize());
        boolean sameDatabase = source != null && target != null && source.getId() != null
                && source.getId().equals(target.getId());
        return sameDatabase ? Math.max(1, (pool - 2) / 2) : Math.max(1, pool - 1);
    }

    /** The largest parallelism any project can reach: the hard ceiling and the pools' seats. */
    public static int effectiveCap(SyncProperties properties, DatabaseConfig source,
                                   DatabaseConfig target) {
        return Math.max(1, Math.min(MAX_PARALLELISM, poolBound(properties, source, target)));
    }
}
