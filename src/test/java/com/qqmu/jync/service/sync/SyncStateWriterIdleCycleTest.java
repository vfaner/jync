package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * A successful cycle with nothing to persist must not write the progress row.
 *
 * <p>{@code advanceCursor} runs after every successful table, every poll interval. An idle table
 * therefore rewrites an identical row every two seconds — a pointless UPDATE per table per cycle,
 * on a database that is usually the customer's shared production server. The row is only worth
 * writing when one of the values it carries actually moves; the first cycle still has to record
 * {@code initialLoadDone}, and a moved cursor or a switched strategy still has to be saved.
 */
class SyncStateWriterIdleCycleTest {

    private static final long PROJECT_ID = 5L;
    private static final CursorStrategy STRATEGY =
            CursorStrategy.identity("ID", Types.BIGINT, "test");

    private SyncProgressRepository progressRepository;
    private SyncStateWriter writer;

    @BeforeEach
    void setUp() {
        progressRepository = mock(SyncProgressRepository.class);
        writer = new SyncStateWriter(progressRepository, mock(ChangeLogRepository.class),
                mock(MetadataSnapshotService.class), mock(ChunkCheckpointRepository.class));
    }

    /** A row already converged with this strategy: nothing about it should change. */
    private SyncProgress convergedProgress() {
        SyncProgress progress = new SyncProgress();
        progress.setProjectId(PROJECT_ID);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("ORDERS");
        progress.setLastSyncValue("41");
        progress.setCursorColumn(STRATEGY.getColumn());
        progress.setCursorStrategy(STRATEGY.getKind().name());
        progress.setInitialLoadDone(true);
        progress.setRowsSyncedTotal(10L);
        return progress;
    }

    @Test
    void anIdleCycleDoesNotWriteTheProgressRow() {
        SyncProgress progress = convergedProgress();

        writer.advanceCursor(progress, new DataSyncService.TableSyncResult(), STRATEGY);

        verify(progressRepository, never()).save(any());
    }

    @Test
    void theFirstSuccessfulCycleStillRecordsTheInitialLoad() {
        SyncProgress progress = convergedProgress();
        progress.setInitialLoadDone(false);

        writer.advanceCursor(progress, new DataSyncService.TableSyncResult(), STRATEGY);

        verify(progressRepository, times(1)).save(progress);
        assertThat(progress.getInitialLoadDone()).isTrue();
    }

    @Test
    void aMovedCursorIsSaved() {
        SyncProgress progress = convergedProgress();
        DataSyncService.TableSyncResult result = new DataSyncService.TableSyncResult();
        ReflectionTestUtils.setField(result, "newCursorValue", "42");

        writer.advanceCursor(progress, result, STRATEGY);

        verify(progressRepository, times(1)).save(progress);
        assertThat(progress.getLastSyncValue()).isEqualTo("42");
    }

    @Test
    void aSwitchedStrategyIsSaved() {
        SyncProgress progress = convergedProgress();
        progress.setCursorColumn("OLD_ID");
        progress.setCursorStrategy(CursorStrategy.Kind.TIMESTAMP.name());

        writer.advanceCursor(progress, new DataSyncService.TableSyncResult(), STRATEGY);

        verify(progressRepository, times(1)).save(progress);
        assertThat(progress.getCursorColumn()).isEqualTo(STRATEGY.getColumn());
    }

    @Test
    void aCycleThatChangedRowsIsSaved() {
        SyncProgress progress = convergedProgress();
        DataSyncService.TableSyncResult result = new DataSyncService.TableSyncResult();
        ReflectionTestUtils.setField(result, "rowsInserted", 1);

        writer.advanceCursor(progress, result, STRATEGY);

        verify(progressRepository, times(1)).save(progress);
        assertThat(progress.getRowsSyncedTotal()).isEqualTo(11L);
    }
}
