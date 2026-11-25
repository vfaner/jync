package com.qqmu.jync.service.fanout;

import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.cdc.CdcTableMetaSupplier;

/** Adapts the shared binding resolver to the binlog handler's supplier contract. */
public final class FanoutMetaSupplier implements CdcTableMetaSupplier {

    private final FanoutBindingResolver resolver;

    public FanoutMetaSupplier(FanoutBindingResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public CdcTableBinding binding(String database, String table) {
        return resolver.resolve(database, table);
    }

    @Override
    public void invalidate(String database, String table) {
        // Binlog DDL identifies no table; over-invalidation is safe and cheap.
        resolver.invalidateAll();
    }
}
