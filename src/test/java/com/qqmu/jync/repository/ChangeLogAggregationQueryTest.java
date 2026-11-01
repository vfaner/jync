package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;

/**
 * The page's change curves are grouped by the metadata store, not in Java — a week of a busy
 * project would otherwise be loaded row by row. That puts two native queries that the unit
 * tests cannot see on the critical path, so they are exercised against the real H2 store here.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:changelogagg;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class ChangeLogAggregationQueryTest {

    private static final Long PROJECT = 42L;
    private static final Instant BASE = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired private ChangeLogRepository repository;

    @Test
    void theMinuteCurveYieldsOneRowPerBucketAndType() {
        repository.save(change("orders", ChangeType.INSERT, BASE.plusSeconds(5)));
        repository.save(change("orders", ChangeType.INSERT, BASE.plusSeconds(40)));
        repository.save(change("orders", ChangeType.UPDATE, BASE.plusSeconds(41)));
        repository.save(change("items", ChangeType.DELETE, BASE.plusSeconds(65)));
        repository.flush();

        List<Object[]> rows = repository.minutelyChangesForProject(PROJECT,
                Timestamp.from(BASE.minusSeconds(60)), Timestamp.from(BASE.plusSeconds(300)));

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row[0]).isInstanceOf(Timestamp.class));
        assertThat(rows).extracting(row -> row[1] + ":" + row[2])
                .containsExactlyInAnyOrder("INSERT:2", "UPDATE:1", "DELETE:1");
    }

    @Test
    void anotherProjectsChangesStayOutOfTheWindow() {
        repository.save(change("orders", ChangeType.INSERT, BASE.plusSeconds(5)));
        ChangeLog other = change("orders", ChangeType.INSERT, BASE.plusSeconds(6));
        other.setProjectId(7L);
        repository.save(other);
        repository.flush();

        List<Object[]> rows = repository.minutelyChangesForProject(PROJECT,
                Timestamp.from(BASE.minusSeconds(60)), Timestamp.from(BASE.plusSeconds(300)));

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(((Number) row[2]).longValue()).isEqualTo(1L));
    }

    @Test
    void theHotTableRankingPutsTheBusiestObjectFirstAndStopsAtThePageSize() {
        repository.save(change("orders", ChangeType.INSERT, BASE.plusSeconds(1)));
        repository.save(change("orders", ChangeType.INSERT, BASE.plusSeconds(2)));
        repository.save(change("items", ChangeType.INSERT, BASE.plusSeconds(3)));
        repository.flush();

        List<Object[]> rows = repository.topChangedObjects(PROJECT,
                Timestamp.from(BASE.minusSeconds(60)), Timestamp.from(BASE.plusSeconds(300)),
                PageRequest.of(0, 1));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row[0]).isEqualTo("orders");
            assertThat(((Number) row[1]).longValue()).isEqualTo(2L);
        });
    }

    private ChangeLog change(String object, ChangeType type, Instant at) {
        ChangeLog log = ChangeLog.of(PROJECT, ObjectType.TABLE, object, type, null);
        log.setOccurredAt(at);
        return log;
    }
}
