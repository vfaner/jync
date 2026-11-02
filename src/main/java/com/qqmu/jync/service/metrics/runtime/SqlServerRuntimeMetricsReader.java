package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * SQL Server dynamic management views and performance counters.
 *
 * <p>There is no cumulative batch counter in the DMVs, so the counter is the sum of cached
 * plans' {@code execution_count}. A plan eviction can make that sum fall; the sampler
 * already reports a falling counter as unknown instead of a negative rate.
 *
 * <p>{@code Buffer cache hit ratio} is a fraction counter: its {@code cntr_value} is the
 * numerator and the row named {@code Buffer cache hit ratio base} holds the denominator
 * (cache lookups). Reading the numerator alone yields a page count in the millions — shown
 * as a percentage it clamps to 100 and hides every real change. Both rows are joined on
 * object and instance so a multi-instance/per-NUMA-node counter table cannot pair them up
 * across rows.
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
            "SELECT a.cntr_value, b.cntr_value "
            + "FROM sys.dm_os_performance_counters a "
            + "JOIN sys.dm_os_performance_counters b "
            + "ON a.object_name = b.object_name AND a.instance_name = b.instance_name "
            + "WHERE a.object_name = 'SQLServer:Buffer Manager' "
            + "AND a.counter_name = 'Buffer cache hit ratio' "
            + "AND b.counter_name = 'Buffer cache hit ratio base'";

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
        Double hitPercent = reads.attempt(() -> bufferCacheHitPercent(connection));
        Integer lockWaits = reads.attempt(() -> TolerantReads.scalarInt(connection, LOCK_QUERY));
        reads.requireSomethingReadable(sessions != null || counter != null);
        return new Reading(sessions, counter, hitPercent, lockWaits);
    }

    /** Numerator / base × 100; unknown when either row is missing or the base is zero. */
    private static Double bufferCacheHitPercent(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(HIT_QUERY)) {
            if (!rs.next()) {
                return null;
            }
            Long hits = TolerantReads.parseLong(rs.getString(1));
            Long lookups = TolerantReads.parseLong(rs.getString(2));
            if (hits == null || lookups == null || lookups <= 0) {
                return null;
            }
            return hits * 100.0 / lookups;
        }
    }
}
