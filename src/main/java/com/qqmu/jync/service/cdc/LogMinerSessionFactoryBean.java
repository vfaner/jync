package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

/**
 * Spring adapter around {@link LogMinerSession#open}: the default session factory every
 * LOGMINER_CDC consumer is handed. The dialect is chosen by capability probe, so an Oracle
 * source takes the exact code path v1.6.0 shipped and a DM source uses SYS.DBMS_LOGMNR with
 * its own discovery and command syntax. Extracted into its own @Component so the manager can
 * inject it via constructor and tests can substitute a fake.
 */
@Component
public class LogMinerSessionFactoryBean implements LogMinerSessionFactory {

    @Override
    public LogMinerSession open(Connection source, long startScn, int batchSize)
            throws SQLException {
        return LogMinerSession.open(source, startScn, batchSize, MiningDialects.detect(source));
    }
}
