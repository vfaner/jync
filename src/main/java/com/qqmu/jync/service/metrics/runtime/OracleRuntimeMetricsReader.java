package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code v$} views for the Oracle family: Oracle, DM and YashanDB all model their runtime
 * state on Oracle's dynamic performance views.
 *
 * <p>Sessions, the cumulative statement counter and the lock count are read separately —
 * a monitoring account granted {@code V$SESSION} and {@code V$SYSSTAT} but not
 * {@code V$LOCK} keeps its sessions and QPS figures.
 *
 * <p>DM and YashanDB name these statistics like Oracle, but the names are not verified
 * against a live server here, so a renamed statistic simply yields an unknown figure.
 */
@Component
@Slf4j
public class OracleRuntimeMetricsReader implements RuntimeMetricsReader {

    private static final String SESSION_QUERY = "SELECT COUNT(*) FROM v$session";

    /**
     * Oracle names these statistics in lower case, YashanDB in upper case and with different
     * wording for the read counters, so both namings are asked for and matched
     * case-insensitively. Verified against Oracle 26ai and YashanDB 23.4.
     */
    private static final String STATS_QUERY =
            "SELECT UPPER(name), value FROM v$sysstat WHERE UPPER(name) IN "
            + "('EXECUTE COUNT','SESSION LOGICAL READS','PHYSICAL READS',"
            + "'BUFFER GETS','DISK READS')";

    /**
     * {@code V$LOCK} has no portable wait column — Oracle filters on {@code BLOCK}, YashanDB
     * rejects the name outright — but {@code V$SESSION.LOCKWAIT} exists in both.
     */
    private static final String LOCK_QUERY =
            "SELECT COUNT(*) FROM v$session WHERE lockwait IS NOT NULL";

    @Override
    public boolean supports(DatabaseType type) {
        return type != null && type.getFamily() == DatabaseType.DialectFamily.ORACLE;
    }

    @Override
    public Reading read(Connection connection) throws SQLException {
        TolerantReads reads = new TolerantReads();
        Integer sessions = reads.attempt(() -> TolerantReads.scalarInt(connection, SESSION_QUERY));
        Map<String, Long> stats = reads.attempt(() -> stats(connection));
        Integer lockWaits = reads.attempt(() -> TolerantReads.scalarInt(connection, LOCK_QUERY));
        reads.requireSomethingReadable(sessions != null || stats != null);
        Long counter = stats == null ? null : stats.get("EXECUTE COUNT");
        return new Reading(sessions, counter, hitPercent(stats), lockWaits);
    }

    private Map<String, Long> stats(Connection connection) throws SQLException {
        Map<String, Long> values = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(STATS_QUERY)) {
            while (rs.next()) {
                String name = rs.getString(1);
                Long value = TolerantReads.parseLong(rs.getString(2));
                if (name != null && value != null) {
                    values.put(name.toUpperCase(Locale.ROOT), value);
                }
            }
        }
        return values;
    }

    private Double hitPercent(Map<String, Long> stats) {
        if (stats == null) {
            return null;
        }
        return TolerantReads.hitPercentOfLogicalReads(
                firstOf(stats, "SESSION LOGICAL READS", "BUFFER GETS"),
                firstOf(stats, "PHYSICAL READS", "DISK READS"));
    }

    private Long firstOf(Map<String, Long> stats, String... names) {
        for (String name : names) {
            Long value = stats.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
