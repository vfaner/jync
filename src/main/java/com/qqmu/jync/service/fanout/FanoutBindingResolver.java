package com.qqmu.jync.service.fanout;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.sync.SyncContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Source-side binding cache shared by every target of a fan-out project: one catalog read per
 * table, not one per target. The capture owns the source connection; targets never open the
 * source themselves.
 *
 * <p>A table whose metadata cannot be read, or that has no primary key, resolves to null and
 * the target workers skip it (CDC cannot address a keyless row reliably).
 */
@Slf4j
public final class FanoutBindingResolver implements CdcBindingResolver {

    private final SyncContext sourceCtx;
    private final Map<String, CdcTableBinding> cache = new ConcurrentHashMap<>();
    /**
     * One JDBC connection serves every target worker; JDBC connections are not thread-safe
     * (SQL Server's driver corrupts its TDS stream outright when two threads read metadata
     * concurrently, closing the connection and making both targets skip the table). Catalog
     * reads are rare — once per table — so serializing them costs nothing.
     */
    private final Object readLock = new Object();
    /**
     * Last WARN per table for a failed catalog read. One high-volume table whose metadata
     * cannot be read must not print a warning per row event; a minute between warnings keeps
     * it visible without drowning the log.
     */
    private final Map<String, Long> lastWarnAt = new ConcurrentHashMap<>();
    private static final long WARN_INTERVAL_MS = 60_000L;

    public FanoutBindingResolver(SyncContext sourceCtx) {
        this.sourceCtx = sourceCtx;
    }

    @Override
    public CdcTableBinding resolve(String schema, String table) {
        if (table == null) {
            return null;
        }
        String key = (schema == null ? "" : schema) + "." + table;
        CdcTableBinding cached = cache.get(key);
        if (cached != null) {
            return cached == NULL_BINDING ? null : cached;
        }
        try {
            TableMeta meta;
            synchronized (readLock) {
                // A connection per (cache-miss) read: the runtime holds no long-lived
                // binding connection, so a source restart or an idle timeout cannot leave
                // every target resolving against a dead socket.
                try (Connection conn = sourceCtx.getConnectionProvider()
                        .open(sourceCtx.getSourceConfig())) {
                    meta = sourceCtx.getSourceReader()
                            .readTable(conn, sourceCtx.getSourceSchema(), table);
                }
            }
            if (meta == null || !meta.hasPrimaryKey()) {
                cache.put(key, NULL_BINDING);
                return null;
            }
            CdcTableBinding binding = CdcTableBinding.of(schema, table, meta,
                    sourceCtx.getConfig().targetTableName(table), null);
            cache.put(key, binding);
            return binding;
        } catch (SQLException | RuntimeException e) {
            // NOT cached, and NOT reported as null: null means "this table is not synced",
            // which the applier treats as rows to skip and then ACKS. A failed catalog read
            // must fail the transaction so the worker replays it instead of dropping data.
            long now = System.currentTimeMillis();
            Long last = lastWarnAt.get(key);
            if (last == null || now - last >= WARN_INTERVAL_MS) {
                lastWarnAt.put(key, now);
                log.warn("Fan-out binding for {}.{} unavailable (transaction will retry): {}",
                        schema, table, e.toString());
            } else {
                log.debug("Fan-out binding for {}.{} still unavailable: {}",
                        schema, table, e.toString());
            }
            throw new IllegalStateException("fan-out binding for " + key + " unavailable", e);
        }
    }

    /** A DDL invalidates every cached layout; the next TABLE_MAP re-reads it. */
    public void invalidateAll() {
        cache.clear();
    }

    private static final CdcTableBinding NULL_BINDING =
            CdcTableBinding.of("", "", null, "", null);
}
