package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * One {@code MON_GET_DATABASE} row carries connections, commit/rollback totals and data
 * pool reads. The monitor function needs {@code MON_GET_DATABASE} authority; without it the
 * whole reading fails, and the page says so instead of drawing zeros.
 */
@Component
public class Db2RuntimeMetricsReader implements RuntimeMetricsReader {

    private static final String MONITOR_QUERY =
            "SELECT APPLS_CUR_CONS, "
            + "TOTAL_APP_COMMITS + TOTAL_APP_ROLLBACKS, "
            + "POOL_DATA_L_READS, POOL_DATA_P_READS "
            + "FROM TABLE(MON_GET_DATABASE(-2))";

    @Override
    public boolean supports(DatabaseType type) {
        return type != null && type.getFamily() == DatabaseType.DialectFamily.DB2;
    }

    @Override
    public Reading read(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(MONITOR_QUERY)) {
            if (!rs.next()) {
                return new Reading(null, null, null, null);
            }
            Integer sessions = intAt(rs, 1);
            Long counter = TolerantReads.parseLong(rs.getString(2));
            Long hits = TolerantReads.parseLong(rs.getString(3));
            Long reads = TolerantReads.parseLong(rs.getString(4));
            return new Reading(sessions, counter,
                    TolerantReads.hitPercentOfLogicalReads(hits, reads), null);
        }
    }

    private Integer intAt(ResultSet rs, int column) throws SQLException {
        Long value = TolerantReads.parseLong(rs.getString(column));
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }
}
