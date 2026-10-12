package com.qqmu.jync.service.cdc;

import org.springframework.stereotype.Component;

/**
 * Spring adapter around {@link LogMinerSession#openDefault}: the default session factory
 * every LOGMINER_CDC consumer is handed. Extracted into its own @Component so the manager
 * can inject it via constructor and tests can substitute a fake.
 */
@Component
public class LogMinerSessionFactoryBean implements LogMinerSessionFactory {

    @Override
    public LogMinerSession open(java.sql.Connection source, long startScn, int batchSize)
            throws java.sql.SQLException {
        return LogMinerSession.openDefault(source, startScn, batchSize);
    }
}
