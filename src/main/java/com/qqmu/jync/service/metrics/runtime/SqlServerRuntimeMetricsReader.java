package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * SQL Server dynamic management views and performance counters.
 *
 * <p>There is no cumulative batch counter in the DMVs, so the counter is the sum of cached
 * plans' {@code execution_count}. A plan eviction can make that sum fall; the sampler
 * already reports a falling counter as unknown instead of a negative rate.
 *
 * <p>Every query needs {@code VIEW SERVER STATE}. Losing the lock DMV alone costs only the
 * lock figure.
 */
@Component
public class SqlServerRuntimeMetricsReader implements RuntimeMetricsReader {

    private static final String SESSION_QUERY =
            "SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE is_user_process = 1";

    private static final String COUNTER_QUERY =
            "SELECT SUM(execution_count) FROM sys.dm_exec_query_stats";

    private static final String HIT_QUERY =
            "SELECT MAX(cntr_value) FROM sys.dm_os_performance_counters "
            + "WHERE counter_name = 'Buffer cache hit ratio'";

    private static final String LOCK_QUERY =
            "SELECT COUNT(*) FROM sys.dm_tran_locks WHERE request_status = 'WAIT'";

    @Override
    public boolean supports(DatabaseType type) {
        return type != null && type.getFamily() == DatabaseType.DialectFamily.SQLSERVER;
    }

    @Override
    public Reading read(Connection connection) throws SQLException {
        TolerantReads reads = new TolerantReads();
        Integer sessions = reads.attempt(() -> TolerantReads.scalarInt(connection, SESSION_QUERY));
        Long counter = reads.attempt(() -> TolerantReads.scalarLong(connection, COUNTER_QUERY));
        Double hitPercent = reads.attempt(() -> TolerantReads.scalarDouble(connection, HIT_QUERY));
        Integer lockWaits = reads.attempt(() -> TolerantReads.scalarInt(connection, LOCK_QUERY));
        reads.requireSomethingReadable(sessions != null || counter != null);
        return new Reading(sessions, counter, percent(hitPercent), lockWaits);
    }

    private Double percent(Double value) {
        return value == null ? null : Math.min(100.0, value);
    }
}
