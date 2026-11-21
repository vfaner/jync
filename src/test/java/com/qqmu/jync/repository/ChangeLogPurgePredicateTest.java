package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.util.LogPurgeDates;

/**
 * The date purge's SQL predicate, against a real store: the selected day is included, today
 * always survives, open DDL tasks survive every scope, and the project / errors-only filters
 * narrow exactly like the page they came from.
 */
@DataJpaTest
class ChangeLogPurgePredicateTest {

    @Autowired private ChangeLogRepository repository;

    private final Instant cutoff =
            LogPurgeDates.cutoffFor(LocalDate.now(ZoneId.systemDefault()).minusDays(1));

    @Test
    void anUnscopedPurgeRemovesOldHistoryButKeepsOpenTasksAndToday() {
        ChangeLog open = row(1L, false, null, ChangeLog.RESOLUTION_PENDING, old());
        row(1L, false, null, ChangeLog.RESOLUTION_PROCESSED, old());
        row(1L, true, null, null, old());
        ChangeLog today = row(1L, true, null, null, Instant.now());

        int removed = repository.purgeBefore(cutoff, null, false);

        assertThat(removed).isEqualTo(2);
        assertThat(ids()).containsExactlyInAnyOrder(open.getId(), today.getId());
    }

    @Test
    void aProjectScopedPurgeLeavesOtherProjectsAlone() {
        ChangeLog other = row(2L, true, null, null, old());
        row(1L, true, null, null, old());

        int removed = repository.purgeBefore(cutoff, 1L, false);

        assertThat(removed).isEqualTo(1);
        assertThat(ids()).containsExactly(other.getId());
    }

    @Test
    void anErrorsOnlyPurgeKeepsOldSuccesses() {
        ChangeLog success = row(1L, true, null, null, old());
        row(1L, false, "boom", null, old());

        int removed = repository.purgeBefore(cutoff, null, true);

        assertThat(removed).isEqualTo(1);
        assertThat(ids()).containsExactly(success.getId());
    }

    @Test
    void anErrorsOnlyPurgeStillKeepsOpenDdlTasks() {
        ChangeLog open = row(1L, false, null, ChangeLog.RESOLUTION_PENDING, old());
        row(1L, false, "boom", null, old());

        int removed = repository.purgeBefore(cutoff, null, true);

        assertThat(removed).isEqualTo(1);
        assertThat(ids()).containsExactly(open.getId());
    }

    private static Instant old() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }

    private List<Long> ids() {
        return repository.findAll().stream().map(ChangeLog::getId).toList();
    }

    private ChangeLog row(long projectId, boolean success, String details, String status,
                          Instant at) {
        ChangeLog log = new ChangeLog();
        log.setProjectId(projectId);
        log.setObjectType(ObjectType.TABLE);
        log.setObjectName("t");
        log.setChangeType(success ? ChangeType.CREATE : ChangeType.ERROR);
        log.setSuccess(success);
        log.setDetails(details);
        log.setResolutionStatus(status);
        log.setOccurredAt(at);
        return repository.save(log);
    }
}
