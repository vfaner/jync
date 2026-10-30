package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

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
        ChangeLog log = new ChangeLog();
        log.setProjectId(1L);
        log.setObjectType(ObjectType.TABLE);
        log.setObjectName("t");
        log.setChangeType(ChangeType.INSERT);
        log.setSuccess(true);
        log.setAffectedRows(affectedRows);
        log.setOccurredAt(occurredAt);
        repository.save(log);
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

        List<Object[]> rows = repository.hourlySeriesSince(java.sql.Timestamp.from(since));

        Map<Instant, Object[]> byHour = new TreeMap<>();
        for (Object[] row : rows) {
            byHour.put(((java.sql.Timestamp) row[0]).toInstant(), row);
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
}
