package com.qqmu.jync.service.etl;

import java.sql.SQLException;

import com.qqmu.jync.model.DatabaseConfig;

/**
 * Opens the extraction source of a run. The routing implementation chooses the native MongoDB
 * driver for {@code MONGODB_NATIVE} and JDBC for everything else.
 *
 * <p>Connections are supplied rather than created so tests can hand out direct connections and
 * production keeps using the short-lived pools.
 */
@FunctionalInterface
public interface EtlSourceFactory {

    EtlSource open(DatabaseConfig config, EtlConnectionProvider connections) throws SQLException;
}
