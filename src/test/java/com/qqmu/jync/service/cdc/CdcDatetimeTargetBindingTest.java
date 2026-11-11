package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.sync.JdbcRowMapper;

/**
 * A MySQL {@code DATETIME} is a wall clock, not an instant: the binlog hands it over as
 * epoch-micros with the wall-clock fields stuffed into UTC. Whatever Java value the CDC
 * mapper produces for it must land on the target as that same wall clock.
 *
 * <p>This pins the end-to-end bind, not just the mapper's return type: a
 * {@link java.sql.Timestamp} here carries an instant, and every driver that renders a
 * zone-less timestamp column through the JVM default zone (pgjdbc, ojdbc, H2, …) turns
 * 12:34:56 into 20:34:56 on the documented {@code Asia/Shanghai} deployment. The polling
 * full load binds the server literal instead, so CDC replays of the same row would rewrite
 * values the initial load had written correctly.
 */
class CdcDatetimeTargetBindingTest {

    @Test
    void cdcDatetimeKeepsTheSourceWallClockOnANonMysqlTarget() throws Exception {
        TimeZone original = TimeZone.getDefault();
        // The documented deployment zone (README -Duser.timezone, container TZ).
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        try (Connection target = DriverManager.getConnection(
                "jdbc:h2:mem:cdc_datetime_tz;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")) {
            try (Statement st = target.createStatement()) {
                st.execute("DROP TABLE IF EXISTS orders");
                st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, dt TIMESTAMP)");
            }

            // 2026-10-10 12:34:56.789012 as the binlog delivers a MySQL DATETIME:
            // the wall-clock fields encoded as if they were UTC.
            long micros = Instant.parse("2026-10-10T12:34:56Z").getEpochSecond() * 1_000_000L
                    + 789_012L;
            ColumnMeta dt = new ColumnMeta();
            dt.setName("DT");
            dt.setJdbcType(Types.TIMESTAMP);
            dt.setTypeName("DATETIME");
            Object mapped = CdcRowMapper.mapValue(micros, dt, null, new CdcRuntimeStats());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID", 1L);
            row.put("DT", mapped);
            try (PreparedStatement ps = target.prepareStatement(
                    "INSERT INTO orders (id, dt) VALUES (?, ?)")) {
                JdbcRowMapper.bind(ps, row, List.of("ID", "DT"),
                        Map.of("ID", Types.BIGINT, "DT", Types.TIMESTAMP),
                        new GenericSqlDialect());
                ps.executeUpdate();
            }

            try (Statement st = target.createStatement();
                 ResultSet rs = st.executeQuery("SELECT dt FROM orders")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1, LocalDateTime.class))
                        .isEqualTo(LocalDateTime.of(2026, 10, 10, 12, 34, 56, 789_012_000));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
