package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;

/**
 * {@code resetProject} promises "the next run performs a full reload". Chunk checkpoints are
 * resume points that let a load skip ranges it believes are already copied, so leaving them
 * behind breaks that promise: the reload starts at the old boundary and the skipped ranges
 * never come back.
 */
@DataJpaTest
@Import({SyncStateWriter.class, MetadataSnapshotService.class})
class SyncStateWriterResetProjectTest {

    /** MetadataSnapshotService wants a mapper; the JPA slice does not provide one. */
    @TestConfiguration
    static class Json {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private SyncStateWriter writer;
    @Autowired private SyncProgressRepository progressRepository;
    @Autowired private ChunkCheckpointRepository checkpointRepository;

    @Test
    void resetProjectAlsoDropsTheResumePointsOfInFlightLoads() {
        writer.loadOrCreateProgress(7L, "ORDERS");
        checkpointRepository.save(new ChunkCheckpoint(7L, "ORDERS", 0, 4, "[400000]", 100000,
                ChunkCheckpoint.STATUS_CHUNK, 0L, 800000L));
        checkpointRepository.save(new ChunkCheckpoint(7L, "CUSTOMERS", 1, 0, null, 0,
                ChunkCheckpoint.STATUS_RANGE_DONE, 800000L, 1600000L));

        writer.resetProject(7L);

        assertThat(progressRepository.findByProjectId(7L)).isEmpty();
        assertThat(checkpointRepository.findByProjectId(7L)).isEmpty();
    }

    /**
     * A target table Jync had to recreate starts over. Clearing its cursor is not enough: a
     * checkpoint from an interrupted load would resume the "full" reload past the ranges it
     * already marks as copied — into a table that holds none of those rows.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void forceReloadResetsOnlyThatTablesResumeState() {
        SyncProgress orders = writer.loadOrCreateProgress(7L, "ORDERS");
        orders.setLastSyncValue("42");
        orders.setInitialLoadDone(true);
        orders = progressRepository.saveAndFlush(orders);
        // Flush: the clear runs in its own REQUIRES_NEW transaction, which must be able to
        // see these rows.
        checkpointRepository.saveAndFlush(new ChunkCheckpoint(7L, "ORDERS", 0, 4, "[400000]",
                100000, ChunkCheckpoint.STATUS_CHUNK, 0L, 800000L));
        checkpointRepository.saveAndFlush(new ChunkCheckpoint(7L, "CUSTOMERS", 0, 2, "[200]", 10,
                ChunkCheckpoint.STATUS_CHUNK, 0L, 800000L));

        SyncProgress cleared = writer.forceReload(orders);

        assertThat(cleared.getLastSyncValue()).isNull();
        assertThat(cleared.getInitialLoadDone())
                .as("a table rebuilt from nothing must take the first-load path, not only lose"
                        + " its cursor: a table with no incremental strategy is skipped when"
                        + " initialLoadDone is set")
                .isFalse();
        assertThat(checkpointRepository.findByProjectIdAndTableName(7L, "ORDERS")).isEmpty();
        assertThat(checkpointRepository.findByProjectIdAndTableName(7L, "CUSTOMERS"))
                .hasSize(1);
    }
}
