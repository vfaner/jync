package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.ChunkCheckpoint;
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
}
