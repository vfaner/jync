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
 * A skipped DDL opens a task once. The consumer that filed it may be replaced by a restart
 * before its position moves past the event, and the in-memory dedupe map dies with it — so
 * the identity of an already-filed event has to live in the metadata store. The binlog
 * position is that identity when the source reports one; otherwise the statement text is.
 */
@DataJpaTest
class ChangeLogDdlTaskDedupQueryTest {

    private static final String DDL = "CDC_SKIPPED_DDL:ALTER TABLE t ADD COLUMN c INT";

    @Autowired
    private ChangeLogRepository repository;

    @Test
    void theSameEventIsFoundAgainAfterARestart() {
        task(1L, "binlog.000001", 100L, DDL, ChangeLog.RESOLUTION_PENDING);

        assertThat(repository.countDdlTasksAt(1L, "binlog.000001", 100L, DDL)).isEqualTo(1);
        assertThat(repository.countDdlTasksAt(1L, "binlog.000001", 200L, DDL)).isZero();
        assertThat(repository.countDdlTasksAt(2L, "binlog.000001", 100L, DDL)).isZero();
    }

    @Test
    void aClosedTaskStillIdentifiesTheSameEvent() {
        // The position only advances on commit, so a restart before that re-reads the very
        // event the administrator already dismissed or executed. Counting only open tasks
        // would put it back on the list and resurrect a decision that was already made.
        task(1L, "binlog.000001", 100L, DDL, ChangeLog.RESOLUTION_PROCESSED);

        assertThat(repository.countDdlTasksAt(1L, "binlog.000001", 100L, DDL)).isEqualTo(1);
    }

    @Test
    void aDifferentStatementAtTheSamePositionIsNewWork() {
        // A rebuilt binlog reuses the offset for other statements. The text is part of the
        // event's identity, so a processed task must not swallow them.
        task(1L, "binlog.000001", 100L, DDL, ChangeLog.RESOLUTION_PROCESSED);

        assertThat(repository.countDdlTasksAt(1L, "binlog.000001", 100L,
                "CDC_SKIPPED_DDL:" + "DROP COLUMN c")).isZero();
        assertThat(repository.countOpenDdlTasksByDetails(1L, DDL)).isZero();
    }

    @Test
    void aPositionlessStatementIsMatchedByItsText() {
        task(1L, null, null, DDL, ChangeLog.RESOLUTION_PENDING);

        assertThat(repository.countOpenDdlTasksByDetails(1L, DDL)).isEqualTo(1);
        assertThat(repository.countOpenDdlTasksByDetails(1L, DDL + " ")).isZero();
        assertThat(repository.countOpenDdlTasksByDetails(2L, DDL)).isZero();
    }

    private ChangeLog task(long projectId, String file, Long pos, String details, String status) {
        ChangeLog log = new ChangeLog();
        log.setProjectId(projectId);
        log.setObjectType(ObjectType.PROJECT);
        log.setObjectName("t");
        log.setChangeType(ChangeType.ERROR);
        log.setSuccess(false);
        log.setDetails(details);
        log.setResolutionStatus(status);
        log.setGapStartFile(file);
        log.setGapStartPos(pos);
        log.setOccurredAt(Instant.parse("2026-01-01T00:00:00Z"));
        return repository.save(log);
    }
}
