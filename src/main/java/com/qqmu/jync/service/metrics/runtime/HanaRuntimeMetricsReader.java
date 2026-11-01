package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * HANA's monitoring views: running connections and a cumulative statement count.
 *
 * <p>This build reads no buffer-cache ratio or lock-wait figure for HANA, so both stay
 * unknown rather than being approximated from unrelated counters.
 */
@Component
public class HanaRuntimeMetricsReader implements RuntimeMetricsReader {

    private static final String SESSION_QUERY =
            "SELECT COUNT(*) FROM M_CONNECTIONS WHERE CONNECTION_STATUS = 'RUNNING'";

    private static final String COUNTER_QUERY =
            "SELECT COALESCE(SUM(TOTAL_STATEMENTS_EXECUTED), 0) FROM M_SERVICE_STATISTICS";

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.HANA;
    }

    @Override
    public Reading read(Connection connection) throws SQLException {
        TolerantReads reads = new TolerantReads();
        Integer sessions = reads.attempt(() -> TolerantReads.scalarInt(connection, SESSION_QUERY));
        Long counter = reads.attempt(() -> TolerantReads.scalarLong(connection, COUNTER_QUERY));
        reads.requireSomethingReadable(sessions != null || counter != null);
        return new Reading(sessions, counter, null, null);
    }
}
