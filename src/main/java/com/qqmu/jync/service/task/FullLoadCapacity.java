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

    /**
     * The same ceiling, with the seats other cycles already hold on each configuration.
     *
     * <p>A cycle holds one seat per side (two on a shared configuration), and every worker
     * borrows one seat per side, so {@code seats} cycles leave {@code pool - seats} seats
     * for the workers of all of them combined. A cycle that ignores its neighbours asks for
     * four workers on a pool that has room for one and loses the rest to waiting.
     *
     * <p>Both numbers are in seats, not cycles: the shared-configuration case passes two.
     * Zero (a configuration nobody tracks) counts as one.
     */
    public static int effectiveCap(SyncProperties properties, DatabaseConfig source,
                                   DatabaseConfig target, int sourceSeats, int targetSeats) {
        int pool = Math.max(2, properties.getMaxPoolSize());
        boolean sameDatabase = source != null && target != null && source.getId() != null
                && source.getId().equals(target.getId());
        // A shared configuration seats this cycle twice even when nobody is tracking it
        // (0 seats means "unknown"), so the floor there is 2, not 1.
        int seats = sameDatabase
                ? Math.max(2, Math.max(sourceSeats, targetSeats))
                : Math.max(1, sourceSeats);
        int perConfiguration = (pool - seats) / seats;
        if (!sameDatabase) {
            int onTarget = pool - Math.max(1, targetSeats);
            perConfiguration = Math.min(perConfiguration, onTarget / Math.max(1, targetSeats));
        }
        return Math.max(1, Math.min(MAX_PARALLELISM, perConfiguration));
    }
}
