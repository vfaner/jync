package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;

/**
 * The dashboard trend chart groups the change log by hour. The aggregation must happen in the
 * database — a day of traffic can be hundreds of thousands of rows, and fetching them to bucket
 * them in Java would put exactly that traffic on the metadata store the page is meant to watch.
 */
@DataJpaTest
class ChangeLogHourlySeriesDataTest {

    @Autowired
    private ChangeLogRepository repository;

    private void entry(Instant occurredAt, Integer affectedRows) {
        entry(occurredAt, affectedRows, ChangeType.INSERT);
    }

    private void entry(Instant occurredAt, Integer affectedRows, ChangeType changeType) {
        ChangeLog log = new ChangeLog();
        log.setProjectId(1L);
        log.setObjectType(ObjectType.TABLE);
        log.setObjectName("t");
        log.setChangeType(changeType);
        log.setSuccess(true);
        log.setAffectedRows(affectedRows);
        log.setOccurredAt(occurredAt);
        repository.save(log);
    }

    private static Instant instantOf(Object raw) {
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.LocalDateTime) raw).toInstant(java.time.ZoneOffset.UTC);
    }

    @Test
    void rowsAreBucketedByHourWithTheirChangeAndAffectedRowTotals() {
        Instant since = Instant.parse("2026-01-01T08:00:00Z");
        // Three rows in the 10:00 bucket, one in 09:00, one before the window.
        entry(Instant.parse("2026-01-01T10:00:10Z"), 5);
        entry(Instant.parse("2026-01-01T10:45:00Z"), 7);
        entry(Instant.parse("2026-01-01T10:59:59Z"), null);
        entry(Instant.parse("2026-01-01T09:30:00Z"), 2);
        entry(Instant.parse("2026-01-01T07:59:59Z"), 100);

        List<Object[]> rows = repository.hourlySeriesBetween(
                java.sql.Timestamp.from(since),
                java.sql.Timestamp.from(Instant.parse("2026-01-01T11:00:00Z")));

        Map<Instant, Object[]> byHour = new TreeMap<>();
        for (Object[] row : rows) {
            byHour.put(instantOf(row[0]), row);
        }
        assertThat(byHour.keySet()).containsExactly(
                Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(((Number) byHour.get(Instant.parse("2026-01-01T10:00:00Z"))[1]).longValue())
                .isEqualTo(3L);
        assertThat(((Number) byHour.get(Instant.parse("2026-01-01T10:00:00Z"))[2]).longValue())
                .isEqualTo(12L);
        assertThat(((Number) byHour.get(Instant.parse("2026-01-01T09:00:00Z"))[1]).longValue())
                .isEqualTo(1L);
    }

    /**
     * One point per hour, however many kinds of row the hour holds. The CDC window summaries
     * ({@code INFO} rows carrying upsert/delete counts) and the row changes they summarise must
     * land in the same bucket, and neither may overwrite the other: grouping by change type as
     * well once left an hour holding only the last type's totals, quietly undercounting the
     * chart. The ETL line does not read this query at all — it has its own history table.
     */
    @Test
    void anHourMixingChangeTypesIsStillOneRowWithTheSummedTotals() {
        Instant since = Instant.parse("2026-01-01T08:00:00Z");
        entry(Instant.parse("2026-01-01T10:00:10Z"), 5, ChangeType.INSERT);
        entry(Instant.parse("2026-01-01T10:30:00Z"), 1, ChangeType.INFO);
        entry(Instant.parse("2026-01-01T10:45:00Z"), 3, ChangeType.ERROR);

        List<Object[]> rows = repository.hourlySeriesBetween(
                java.sql.Timestamp.from(since),
                java.sql.Timestamp.from(Instant.parse("2026-01-01T11:00:00Z")));

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(3L);
        assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(9L);
    }

    /**
     * Both ends of the window are real. Without the upper bound a change logged with a
     * clock-skewed future timestamp — the metadata store takes whatever the node's clock says —
     * sat in the 24-hour tile forever and in no bar of the chart.
     */
    @Test
    void rowsOutsideEitherEndOfTheWindowAreExcluded() {
        Instant since = Instant.parse("2026-01-01T08:00:00Z");
        Instant until = Instant.parse("2026-01-01T10:30:00Z");
        entry(Instant.parse("2026-01-01T09:00:00Z"), 2);    // in
        entry(Instant.parse("2026-01-01T10:00:00Z"), 5);    // in
        entry(Instant.parse("2026-01-01T07:59:59Z"), 100);  // before
        entry(Instant.parse("2026-01-01T10:45:00Z"), 200);  // after

        List<Object[]> rows = repository.hourlySeriesBetween(
                java.sql.Timestamp.from(since), java.sql.Timestamp.from(until));

        long changes = 0;
        long affected = 0;
        for (Object[] row : rows) {
            changes += ((Number) row[1]).longValue();
            affected += ((Number) row[2]).longValue();
        }
        assertThat(changes).isEqualTo(2L);
        assertThat(affected).isEqualTo(7L);
    }
}
