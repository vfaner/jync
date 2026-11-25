package com.qqmu.jync.service.fanout;

import com.qqmu.jync.service.cdc.CdcTableBinding;

/**
 * Resolves the source binding (columns, primary key, target table name) a row event needs.
 * The capture side owns the source metadata and passes a cached resolver to every target
 * applier, so N targets share one catalog read instead of N.
 */
@FunctionalInterface
public interface CdcBindingResolver {

    /** @return null when the table is no longer tracked or has no usable binding */
    CdcTableBinding resolve(String schema, String table);
}
