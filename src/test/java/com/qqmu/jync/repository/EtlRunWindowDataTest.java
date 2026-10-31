package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.qqmu.jync.model.EtlRun;

/**
 * The dashboard's ETL line reads this history over the chart's window. Unlike the change log
 * (bucketed in the database) a day of runs is a handful of rows, so the caller does the
 * bucketing and only the window and the ordering matter here.
 */
@DataJpaTest
class EtlRunWindowDataTest {

    @Autowired
    private EtlRunRepository repository;

    private EtlRun run(Instant occurredAt, Integer affectedRows, boolean success) {
        EtlRun run = new EtlRun();
        run.setEtlTaskId(1L);
        run.setAffectedRows(affectedRows);
        run.setSuccess(success);
        run.setOccurredAt(occurredAt);
        return repository.save(run);
    }

    @Test
    void runsInsideTheWindowComeBackOldestFirst() {
        Instant from = Instant.parse("2026-01-01T08:00:00Z");
        Instant to = Instant.parse("2026-01-01T11:00:00Z");
        run(Instant.parse("2026-01-01T10:30:00Z"), 5, true);
        run(Instant.parse("2026-01-01T09:00:00Z"), null, false);
        run(Instant.parse("2026-01-01T07:59:59Z"), 100, true);   // before
        run(Instant.parse("2026-01-01T11:00:01Z"), 200, true);   // after

        List<EtlRun> runs = repository.findByOccurredAtBetweenOrderByOccurredAtAsc(from, to);

        assertThat(runs).extracting(EtlRun::getOccurredAt).containsExactly(
                Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"));
        // A failed run with no rows written must still round-trip: the chart counts attempts.
        assertThat(runs.get(0).getSuccess()).isFalse();
        assertThat(runs.get(0).getAffectedRows()).isNull();
    }
}
