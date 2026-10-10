package com.qqmu.jync.service.sync;

import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Which way an initial full load should read a table.
 *
 * <p>Keyset chunking needs a stable, totally-ordered way to say "give me the next rows after
 * this key". A primary key provides exactly that; its absence does not, and then the load has
 * to fall back to the single streamed read it has always been.
 *
 * <ul>
 *   <li>{@link #KEYSET_NUMERIC} — one integral PK column: the cheapest and most index-friendly
 *       boundary, {@code WHERE pk > ? ORDER BY pk}.
 *   <li>{@link #KEYSET_COMPOSITE} — any other PK shape (multi-column, or a single non-integral
 *       key): still totally ordered, paged with the OR-chain comparison
 *       {@code (a > ?) OR (a = ? AND b > ?)}.
 *   <li>{@link #STREAMING_FALLBACK} — no PK at all: keep the current single-SQL streaming
 *       load. Without a key there is neither a paging boundary nor idempotent upserts, so
 *       chunked resume would be unsound here anyway.
 * </ul>
 */
public enum FullLoadRoute {

    KEYSET_NUMERIC,
    KEYSET_COMPOSITE,
    STREAMING_FALLBACK;

    public static FullLoadRoute route(TableMeta table) {
        if (!table.hasPrimaryKey()) {
            return STREAMING_FALLBACK;
        }
        if (table.getPrimaryKeys().size() == 1
                && table.column(table.getPrimaryKeys().get(0))
                        .map(c -> CursorStrategy.isIntegralType(c.getJdbcType()))
                        .orElse(false)) {
            return KEYSET_NUMERIC;
        }
        return KEYSET_COMPOSITE;
    }
}
