package com.qqmu.jync.service.converter;

import com.qqmu.jync.model.DatabaseType;

/**
 * TDengine read-only dialect: backtick identifiers and {@code LIMIT / OFFSET} paging.
 *
 * <p>TDengine 3.3.6 rejects window functions, so the full-compare strategies that rely on
 * them are off for this source. It never takes part in a write path — the ETL engine writes
 * through the customer's own SQL, and real-time sync treats it as a polling source only.
 */
public class TdengineDialect extends PollingSourceDialect {

    public TdengineDialect() {
        super(DatabaseType.TDENGINE, '`', true);
    }

    @Override
    public boolean supportsWindowFunctions() {
        return false;
    }
}
