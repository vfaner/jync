package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;

/**
 * A table that fails every cycle must not write one identical audit row every cycle.
 *
 * <p>The default poll interval is two seconds, so an unfixable per-table defect files 43,200
 * identical ERROR rows a day — each in its own {@code REQUIRES_NEW} transaction — and buries
 * every other entry in the log. The first occurrence is always kept; identical repeats are
 * suppressed for a minute, and a changed message is a new fact and gets its own row.
 */
class SyncStateWriterErrorDedupTest {

    private static final long PROJECT_ID = 3L;

    private ChangeLogRepository changeLogRepository;
    private SyncStateWriter writer;

    @BeforeEach
    void setUp() {
        changeLogRepository = mock(ChangeLogRepository.class);
        writer = new SyncStateWriter(mock(SyncProgressRepository.class), changeLogRepository,
                mock(MetadataSnapshotService.class), mock(ChunkCheckpointRepository.class));
    }

    @Test
    void anIdenticalFailureIsWrittenOnceWithinTheWindow() {
        for (int i = 0; i < 5; i++) {
            writer.recordLog(PROJECT_ID, ObjectType.DATA, "ORDERS", ChangeType.ERROR,
                    "column mapper blew up", false, 0);
        }

        verify(changeLogRepository, times(1)).save(any());
    }

    @Test
    void aChangedMessageIsANewFactAndIsWritten() {
        writer.recordLog(PROJECT_ID, ObjectType.DATA, "ORDERS", ChangeType.ERROR,
                "column mapper blew up", false, 0);
        writer.recordLog(PROJECT_ID, ObjectType.DATA, "ORDERS", ChangeType.ERROR,
                "constraint rejected row 42", false, 0);

        verify(changeLogRepository, times(2)).save(any());
    }

    @Test
    void theSameMessageOnDifferentTablesIsNotSuppressed() {
        writer.recordLog(PROJECT_ID, ObjectType.DATA, "ORDERS", ChangeType.ERROR, "boom", false, 0);
        writer.recordLog(PROJECT_ID, ObjectType.DATA, "ITEMS", ChangeType.ERROR, "boom", false, 0);

        verify(changeLogRepository, times(2)).save(any());
    }

    @Test
    void successEntriesAreNeverSuppressed() {
        writer.recordLog(PROJECT_ID, ObjectType.INDEX, "ORDERS", ChangeType.CREATE, "created", true, 0);
        writer.recordLog(PROJECT_ID, ObjectType.INDEX, "ORDERS", ChangeType.CREATE, "created", true, 0);

        verify(changeLogRepository, times(2)).save(any());
    }

    @Test
    void theWindowExpiresAndTheFailureIsRecordedAgain() {
        String key = SyncStateWriter.errorKey(PROJECT_ID, ObjectType.DATA, "ORDERS",
                ChangeType.ERROR, "boom");

        assertThat(writer.isDuplicateFailure(key, 1_000)).as("first sight").isFalse();
        assertThat(writer.isDuplicateFailure(key, 30_000)).as("inside the window").isTrue();
        assertThat(writer.isDuplicateFailure(key, 1_000 + SyncStateWriter.ERROR_DEDUP_WINDOW_MS + 1))
                .as("a periodic reminder keeps a long-running failure visible").isFalse();
    }

    @Test
    void theSameDdlAtAnotherPositionIsANewTask() {
        // A column is dropped and added again: the statement reads exactly the same, but it
        // is a different binlog event — and therefore a different task.
        writer.recordSkippedDdl(PROJECT_ID, "app", "ALTER TABLE t ADD COLUMN c INT",
                "binlog.000001", 100L);
        writer.recordSkippedDdl(PROJECT_ID, "app", "ALTER TABLE t ADD COLUMN c INT",
                "binlog.000001", 200L);

        verify(changeLogRepository, times(2)).save(any());
    }

    private static final String DDL_DETAILS =
            SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE t ADD COLUMN c INT";

    @Test
    void theSameDdlEventDeliveredTwiceIsWrittenOnce() {
        // A reconnect replays the very same event from the unchanged position. The first
        // write is on record; the second consults it.
        when(changeLogRepository.countDdlTasksAt(PROJECT_ID, "binlog.000001", 100L, DDL_DETAILS))
                .thenReturn(0L, 1L);
        writer.recordSkippedDdl(PROJECT_ID, "app", "ALTER TABLE t ADD COLUMN c INT",
                "binlog.000001", 100L);
        writer.recordSkippedDdl(PROJECT_ID, "app", "ALTER TABLE t ADD COLUMN c INT",
                "binlog.000001", 100L);

        verify(changeLogRepository, times(1)).save(any());
    }

    @Test
    void aRestartStillRecognisesTheTaskFiledByThePreviousProcess() {
        // The in-memory dedupe died with the previous process. The task row is what
        // survives a restart, so the check must consult the database — otherwise every
        // reconnect files the same DDL again and buries the operator in duplicates.
        when(changeLogRepository.countDdlTasksAt(PROJECT_ID, "binlog.000001", 100L, DDL_DETAILS))
                .thenReturn(1L);

        writer.recordSkippedDdl(PROJECT_ID, "app", "ALTER TABLE t ADD COLUMN c INT",
                "binlog.000001", 100L);

        verify(changeLogRepository, never()).save(any());
    }

    @Test
    void aClosedTaskStillKeepsItsEventFromBeingFiledAgain() {
        // Dismissed or already executed — the position did not move past the event, so a
        // restart re-reads the same statement. Counting only open tasks would raise the
        // alert again and resurrect a decision the administrator already made.
        when(changeLogRepository.countDdlTasksAt(PROJECT_ID, "binlog.000001", 100L, DDL_DETAILS))
                .thenReturn(1L);

        writer.recordSkippedDdl(PROJECT_ID, "app", "ALTER TABLE t ADD COLUMN c INT",
                "binlog.000001", 100L);

        verify(changeLogRepository, never()).save(any());
    }

    @Test
    void aPositionlessDdlIsDedupedByItsStatementAcrossRestarts() {
        // LogMiner DDL carries no binlog file/position: the statement text is all there is.
        String details = SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE \"SRC\".\"ORDERS\" ADD (\"NOTE\" VARCHAR2(64))";
        when(changeLogRepository.countOpenDdlTasksByDetails(PROJECT_ID, details))
                .thenReturn(1L);

        writer.recordSkippedDdl(PROJECT_ID, "ORDERS",
                "ALTER TABLE \"SRC\".\"ORDERS\" ADD (\"NOTE\" VARCHAR2(64))");

        verify(changeLogRepository, never()).save(any());
    }
}
