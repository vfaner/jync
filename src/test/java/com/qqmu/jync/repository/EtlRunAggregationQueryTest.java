package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.EtlRun;

/**
 * The dashboard tile that says "Changes (24h)" now adds the ETL history to the change log, and
 * the tile is refreshed every ten seconds. That puts two aggregate queries on the critical path
 * — the unit test stubs them, so the SQL itself is exercised against the real H2 store here.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:etlrunagg;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class EtlRunAggregationQueryTest {

    private static final Instant BASE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant TO = BASE.plusSeconds(300);

    @Autowired private EtlRunRepository repository;

    @Test
    void itCountsRunsAndSumsRowsInsideTheWindowOnly() {
        repository.save(run(BASE.plusSeconds(5), 4));
        repository.save(run(BASE.plusSeconds(40), 6));
        repository.save(run(BASE.minusSeconds(60), 100));
        repository.flush();

        assertThat(repository.countByOccurredAtBetween(BASE, TO)).isEqualTo(2);
        assertThat(repository.sumAffectedRowsBetween(BASE, TO)).isEqualTo(10);
    }

    @Test
    void aFailedRunStillCountsAndANullRowCountContributesNothing() {
        EtlRun failed = run(BASE.plusSeconds(5), null);
        failed.setSuccess(false);
        repository.save(failed);
        repository.flush();

        assertThat(repository.countByOccurredAtBetween(BASE, TO)).isEqualTo(1);
        assertThat(repository.sumAffectedRowsBetween(BASE, TO)).isZero();
    }

    /** An empty window must read as zero; a null sum would print as a broken tile. */
    @Test
    void anEmptyWindowSumsToZero() {
        assertThat(repository.countByOccurredAtBetween(BASE, TO)).isZero();
        assertThat(repository.sumAffectedRowsBetween(BASE, TO)).isZero();
    }

    private static EtlRun run(Instant occurredAt, Integer affectedRows) {
        EtlRun run = new EtlRun();
        run.setEtlTaskId(1L);
        run.setSuccess(true);
        run.setAffectedRows(affectedRows);
        run.setOccurredAt(occurredAt);
        return run;
    }
}
