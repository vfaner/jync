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
    private final Connection sourceConn;
    private final Map<String, CdcTableBinding> cache = new ConcurrentHashMap<>();

    public FanoutBindingResolver(SyncContext sourceCtx, Connection sourceConn) {
        this.sourceCtx = sourceCtx;
        this.sourceConn = sourceConn;
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
            TableMeta meta = sourceCtx.getSourceReader()
                    .readTable(sourceConn, sourceCtx.getSourceSchema(), table);
            if (meta == null || !meta.hasPrimaryKey()) {
                cache.put(key, NULL_BINDING);
                return null;
            }
            CdcTableBinding binding = CdcTableBinding.of(schema, table, meta,
                    sourceCtx.getConfig().targetTableName(table), null);
            cache.put(key, binding);
            return binding;
        } catch (SQLException | RuntimeException e) {
            log.debug("Fan-out binding for {}.{} unavailable: {}", schema, table, e.toString());
            cache.put(key, NULL_BINDING);
            return null;
        }
    }

    private static final CdcTableBinding NULL_BINDING =
            CdcTableBinding.of("", "", null, "", null);
}
