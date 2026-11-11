package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;

/**
 * A skipped CDC DDL opens a task, not just a history row: clearing the log must not cancel
 * work an administrator still has to do. The retention pruner already keeps open tasks;
 * the "clear" button has to agree, or one click silently disables the projection gate and
 * removes the replay entry point.
 */
@DataJpaTest
class ChangeLogClearKeepsOpenDdlTasksTest {

    @Autowired
    private ChangeLogRepository repository;

    @Test
    void clearingFailuresOfOneProjectKeepsItsOpenDdlTask() {
        ChangeLog open = task(1L, ChangeLog.RESOLUTION_PENDING);
        ChangeLog processed = task(1L, ChangeLog.RESOLUTION_PROCESSED);
        ChangeLog failure = failure(1L);
        ChangeLog otherProject = failure(2L);

        int removed = repository.deleteByProjectIdAndSuccessFalse(1L);

        // The processed task and the ordinary failure are history; the open task is work.
        assertThat(removed).isEqualTo(2);
        assertThat(ids()).containsExactlyInAnyOrder(open.getId(), otherProject.getId());
    }

    @Test
    void clearingEveryFailureKeepsOpenDdlTasksOfAllProjects() {
        ChangeLog open = task(1L, ChangeLog.RESOLUTION_PENDING);
        task(2L, ChangeLog.RESOLUTION_PROCESSED);
        failure(2L);

        int removed = repository.deleteAllSuccessFalse();

        assertThat(removed).isEqualTo(2);
        assertThat(ids()).containsExactly(open.getId());
    }

    @Test
    void aPlainProjectClearKeepsItsOpenDdlTask() {
        ChangeLog open = task(1L, ChangeLog.RESOLUTION_PENDING);
        failure(1L);
        ChangeLog otherProject = task(2L, ChangeLog.RESOLUTION_PENDING);

        int removed = repository.deleteByProjectIdKeepingOpenDdlTasks(1L);

        assertThat(removed).isEqualTo(1);
        assertThat(ids()).containsExactlyInAnyOrder(open.getId(), otherProject.getId());
    }

    @Test
    void aPlainGlobalClearKeepsOpenDdlTasks() {
        ChangeLog open = task(1L, ChangeLog.RESOLUTION_PENDING);
        task(1L, ChangeLog.RESOLUTION_PROCESSED);
        failure(2L);

        int removed = repository.deleteAllBulkKeepingOpenDdlTasks();

        assertThat(removed).isEqualTo(2);
        assertThat(ids()).containsExactly(open.getId());
    }

    /** Ids still in the table; a fresh select, so the delete is reflected. */
    private java.util.List<Long> ids() {
        return repository.findAll().stream().map(ChangeLog::getId).toList();
    }

    private ChangeLog task(long projectId, String status) {
        return row(projectId, false,
                "CDC_SKIPPED_DDL:ALTER TABLE t ADD COLUMN c INT", status);
    }

    private ChangeLog failure(long projectId) {
        return row(projectId, false, "connection refused", null);
    }

    private ChangeLog row(long projectId, boolean success, String details, String status) {
        ChangeLog log = new ChangeLog();
        log.setProjectId(projectId);
        log.setObjectType(ObjectType.TABLE);
        log.setObjectName("t");
        log.setChangeType(success ? ChangeType.CREATE : ChangeType.ERROR);
        log.setSuccess(success);
        log.setDetails(details);
        log.setResolutionStatus(status);
        log.setOccurredAt(Instant.parse("2026-01-01T00:00:00Z"));
        return repository.save(log);
    }
}
