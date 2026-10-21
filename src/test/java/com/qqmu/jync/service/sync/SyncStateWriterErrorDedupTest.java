package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
}
