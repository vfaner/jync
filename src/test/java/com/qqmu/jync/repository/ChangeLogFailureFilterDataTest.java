package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;

/**
 * The failure-only view against a real H2 schema: the derived queries must filter on
 * {@code success = false} and keep the newest-first order the page renders.
 */
@DataJpaTest
class ChangeLogFailureFilterDataTest {

    @Autowired
    private ChangeLogRepository repository;

    private ChangeLog entry(long projectId, String name, boolean success, Instant occurredAt) {
        ChangeLog log = new ChangeLog();
        log.setProjectId(projectId);
        log.setObjectType(ObjectType.TABLE);
        log.setObjectName(name);
        log.setChangeType(success ? ChangeType.CREATE : ChangeType.ERROR);
        log.setSuccess(success);
        // Explicit times: relying on Instant.now() ties rows within one microsecond, after
        // which the descending order between them is database-defined.
        log.setOccurredAt(occurredAt);
        repository.save(log);
        return log;
    }

    @Test
    void theFailureQueriesReturnOnlyUnsuccessfulRowsNewestFirst() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // Two projects interleaved in time, one error and one success each.
        entry(1L, "a-good", true, base.plusSeconds(1));
        entry(1L, "a-bad", false, base.plusSeconds(2));
        entry(2L, "b-bad", false, base.plusSeconds(3));
        entry(2L, "b-good", true, base.plusSeconds(4));

        Pageable pageable = PageRequest.of(0, 20);

        Page<ChangeLog> allFailures =
                repository.findBySuccessFalseOrderByOccurredAtDesc(pageable);
        assertThat(allFailures.getContent())
                .extracting(ChangeLog::getObjectName)
                .containsExactly("b-bad", "a-bad");

        Page<ChangeLog> projectFailures =
                repository.findByProjectIdAndSuccessFalseOrderByOccurredAtDesc(1L, pageable);
        assertThat(projectFailures.getContent())
                .extracting(ChangeLog::getObjectName)
                .containsExactly("a-bad");

        assertThat(repository.countBySuccessFalse()).isEqualTo(2);
        assertThat(repository.countByProjectIdAndSuccessFalse(1L)).isEqualTo(1);
    }

    @Test
    void aCleanHistoryIsAnEmptyFilteredPageNotAnError() {
        entry(1L, "good", true, Instant.parse("2026-01-01T00:00:00Z"));

        Page<ChangeLog> failures =
                repository.findBySuccessFalseOrderByOccurredAtDesc(PageRequest.of(0, 20));

        assertThat(failures.getContent()).isEmpty();
        assertThat(repository.countBySuccessFalse()).isZero();
    }
}
