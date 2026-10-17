package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The per-table twin of the dashboard's timestamp: 上次同步 in the progress table must also mean
 * "when this table last delivered a change", not "when its cursor was last carried forward".
 *
 * <p>{@code advanceCursor} runs on every successful cycle — that is what keeps an idle table's
 * cursor from re-reading the same window — so the timestamp cannot ride along with it.
 */
@DataJpaTest
@Import({SyncStateWriter.class, MetadataSnapshotService.class})
class SyncStateWriterLastSyncTimeTest {

    /** MetadataSnapshotService wants a mapper; the JPA slice does not provide one. */
    @TestConfiguration
    static class Json {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final Instant LAST_REAL_SYNC = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired private SyncStateWriter writer;
    @Autowired private SyncProgressRepository progressRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNew;

    @BeforeEach
    void setUp() {
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Seeds a row that looks like an already-running table, then hands back the detached copy. */
    private SyncProgress seededProgress(long projectId, String table) {
        writer.loadOrCreateProgress(projectId, table);
        requiresNew.executeWithoutResult(status -> {
            SyncProgress p = progressRepository
                    .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, table)
                    .orElseThrow(IllegalStateException::new);
            p.setLastSyncValue("41");
            p.setInitialLoadDone(true);
            p.setLastSyncTime(LAST_REAL_SYNC);
            progressRepository.save(p);
        });
        return writer.loadOrCreateProgress(projectId, table);
    }

    private Instant lastSyncTimeOf(long projectId, String table) {
        return progressRepository
                .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, table)
                .orElseThrow(IllegalStateException::new)
                .getLastSyncTime();
    }

    private static DataSyncService.TableSyncResult resultWith(String field, int value) {
        DataSyncService.TableSyncResult result = new DataSyncService.TableSyncResult();
        // The counts have no setters by design — the engine fills them while copying. Reflecting
        // in the test keeps a test-only mutation path out of the production DTO.
        ReflectionTestUtils.setField(result, field, value);
        return result;
    }

    @Test
    @DisplayName("a cycle that moved no rows leaves the table's 上次同步 alone")
    void anIdleCycleDoesNotMoveTheTablesLastSyncTime() {
        SyncProgress progress = seededProgress(911L, "orders");

        writer.advanceCursor(progress, new DataSyncService.TableSyncResult(),
                CursorStrategy.identity("id", Types.INTEGER, "test"));

        assertThat(lastSyncTimeOf(911L, "orders")).isEqualTo(LAST_REAL_SYNC);
    }

    @Test
    @DisplayName("a cycle that changed rows moves it")
    void aCycleThatChangedRowsMovesTheTablesLastSyncTime() {
        SyncProgress progress = seededProgress(912L, "orders");

        writer.advanceCursor(progress, resultWith("rowsInserted", 1),
                CursorStrategy.identity("id", Types.INTEGER, "test"));

        assertThat(lastSyncTimeOf(912L, "orders")).isAfter(LAST_REAL_SYNC);
    }

    @Test
    @DisplayName("a delete-only cycle still counts as a change")
    void aDeleteOnlyCycleMovesTheTablesLastSyncTime() {
        SyncProgress progress = seededProgress(913L, "orders");

        writer.advanceCursor(progress, resultWith("rowsDeleted", 1),
                CursorStrategy.identity("id", Types.INTEGER, "test"));

        assertThat(lastSyncTimeOf(913L, "orders")).isAfter(LAST_REAL_SYNC);
    }
}
