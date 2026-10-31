package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.connection.DataSourceManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Short-lived pools for one ETL run. Each endpoint gets its own two-connection pool, and both
 * pools are closed when the run ends — an ETL job must never borrow from, or linger in, the
 * pools the real-time sync engine is using.
 */
public final class EtlConnections implements EtlConnectionProvider, AutoCloseable {

    private final DataSourceManager dataSourceManager;
    private final Map<Long, HikariDataSource> pools = new HashMap<>();

    public EtlConnections(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @Override
    public Connection open(DatabaseConfig config) throws SQLException {
        Long key = config.getId() != null
                ? config.getId() : (long) System.identityHashCode(config);
        HikariDataSource pool = pools.get(key);
        if (pool == null) {
            pool = dataSourceManager.createTransientDataSource(config);
            pools.put(key, pool);
        }
        return pool.getConnection();
    }

    @Override
    public void close() {
        for (HikariDataSource pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }
}
