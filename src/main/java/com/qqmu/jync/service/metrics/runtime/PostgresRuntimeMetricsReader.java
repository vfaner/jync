package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL-family catalog views.
 *
 * <p>The counter is <em>transactions</em>, not statements: {@code pg_stat_database} carries
 * no statement counter unless the {@code pg_stat_statements} extension is installed, and
 * requiring an extension would turn a working monitor into a permission error. The chart
 * label says "per second" and the page names the metric for what it is.
 *
 * <p>{@code pg_locks} is read on its own: a role without SELECT on the lock view loses the
 * lock figure, not the sessions and cache figures it can already see.
 */
@Component
@Slf4j
public class PostgresRuntimeMetricsReader implements RuntimeMetricsReader {

    private static final String DATABASE_QUERY =
            "SELECT (SELECT count(*) FROM pg_stat_activity), "
            + "COALESCE(SUM(xact_commit + xact_rollback), 0), "
            + "COALESCE(SUM(blks_hit), 0), "
            + "COALESCE(SUM(blks_read), 0) "
            + "FROM pg_stat_database";

    private static final String LOCK_QUERY =
            "SELECT count(*) FROM pg_locks WHERE NOT granted";

    @Override
    public boolean supports(DatabaseType type) {
        return type != null && type.getFamily() == DatabaseType.DialectFamily.POSTGRES;
    }

    @Override
    public Reading read(Connection connection) throws SQLException {
        Integer sessions;
        Long counter;
        Long hits;
        Long reads;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(DATABASE_QUERY)) {
            if (!rs.next()) {
                return new Reading(null, null, null, null);
            }
            sessions = (int) rs.getLong(1);
            counter = rs.getLong(2);
            hits = rs.getLong(3);
            reads = rs.getLong(4);
        }
        return new Reading(sessions, counter, hitPercent(hits, reads), lockWaits(connection));
    }

    private Integer lockWaits(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(LOCK_QUERY)) {
            return rs.next() ? rs.getInt(1) : null;
        } catch (SQLException e) {
            log.debug("pg_locks is not readable: {}", e.getMessage());
            return null;
        }
    }

    private Double hitPercent(Long hits, Long reads) {
        if (hits == null || reads == null || hits + reads <= 0) {
            return null;
        }
        return hits * 100.0 / (hits + reads);
    }
}
