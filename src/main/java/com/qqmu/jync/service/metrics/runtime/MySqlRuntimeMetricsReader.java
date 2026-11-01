package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code SHOW GLOBAL STATUS} for every MySQL-protocol preset.
 *
 * <p>No extra privilege is needed: a plain connection may read global status on MySQL,
 * MariaDB, OceanBase, TiDB, GoldenDB, TDSQL, PolarDB, GreatDB, UPDRDB, KunDB and GBase.
 * A proxy that hides the InnoDB counters simply yields nulls for those fields.
 */
@Component
@Slf4j
public class MySqlRuntimeMetricsReader implements RuntimeMetricsReader {

    private static final String STATUS_QUERY = "SHOW GLOBAL STATUS WHERE Variable_name IN ("
            + "'Threads_connected','Questions',"
            + "'Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads',"
            + "'Innodb_row_lock_current_waits')";

    @Override
    public boolean supports(DatabaseType type) {
        return type != null && type.getFamily() == DatabaseType.DialectFamily.MYSQL;
    }

    @Override
    public Reading read(Connection connection) throws SQLException {
        Map<String, String> status = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(STATUS_QUERY)) {
            while (rs.next()) {
                status.put(rs.getString(1), rs.getString(2));
            }
        }
        Long requests = number(status, "Innodb_buffer_pool_read_requests");
        Long reads = number(status, "Innodb_buffer_pool_reads");
        return new Reading(
                intValue(status, "Threads_connected"),
                number(status, "Questions"),
                hitPercent(requests, reads),
                intValue(status, "Innodb_row_lock_current_waits"));
    }

    private Integer intValue(Map<String, String> status, String variable) {
        Long value = number(status, variable);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    /** A value the server did not report, or reported as something other than a number. */
    private Long number(Map<String, String> status, String variable) {
        String raw = status.get(variable);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("MySQL status {} is not numeric: {}", variable, raw);
            return null;
        }
    }

    private Double hitPercent(Long requests, Long reads) {
        if (requests == null || reads == null || requests + reads <= 0) {
            return null;
        }
        return requests * 100.0 / (requests + reads);
    }
}
