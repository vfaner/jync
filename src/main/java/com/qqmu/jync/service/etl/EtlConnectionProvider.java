package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.SQLException;

import com.qqmu.jync.model.DatabaseConfig;

/**
 * Opens the two connections an ETL run needs. Production supplies connections from short-lived
 * pools that are closed after the run; tests can hand out direct JDBC connections.
 */
@FunctionalInterface
public interface EtlConnectionProvider {

    Connection open(DatabaseConfig config) throws SQLException;
}
