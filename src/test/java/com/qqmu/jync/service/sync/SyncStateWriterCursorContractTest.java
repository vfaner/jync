package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * The cursor-row handoff between an audit repair and the cursor advance that follows it in the
 * same cycle.
 *
 * <p>Every {@link SyncStateWriter} call commits in its own {@code REQUIRES_NEW} transaction, so
 * the entity a cycle carries between calls is detached, and {@code save()} merges it into a
 * fresh persistence context whose version is bumped at commit. A caller that keeps its own
 * reference after such a write therefore holds a stale version — which is exactly what made a
 * mid-run target truncate explode with {@code StaleObjectStateException}: the row-count audit
 * cleared the cursor (committing version N+1) and the cycle later advanced the cursor with its
 * version-N instance.
 *
 * <p>The contract pinned here: {@code clearCursor} hands back the instance to continue with,
 * and continuing with the caller's old reference fails the optimistic lock. The writer is
 * imported as a real bean so the calls run through the transactional proxy — committed
 * {@code REQUIRES_NEW} transactions and Spring's exception translation, same as production.
 */
@DataJpaTest
@Import({SyncStateWriter.class, MetadataSnapshotService.class})
class SyncStateWriterCursorContractTest {

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
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNew;

    @BeforeEach
    void setUp() {
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Seeds a cursor the way a completed cycle would — in its own committed transaction — and
     * returns the detached row a fresh cycle then loads, mirroring
     * {@code SyncEngine.syncOneTable}'s opening call.
     */
    private SyncProgress seededProgress(long projectId, String table, String cursorValue) {
        writer.loadOrCreateProgress(projectId, table);
        requiresNew.executeWithoutResult(status -> {
            SyncProgress p = progressRepository
                    .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, table)
                    .orElseThrow(IllegalStateException::new);
            p.setLastSyncValue(cursorValue);
            p.setInitialLoadDone(true);
            progressRepository.save(p);
        });
        return writer.loadOrCreateProgress(projectId, table);
    }

    private SyncProgress reload(long projectId, String table) {
        SyncProgress row = requiresNew.execute(status -> progressRepository
                .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, table)
                .orElseThrow(IllegalStateException::new));
        return row;
    }

    /**
     * The fixed cycle: audit repair clears the cursor, the full reload runs, and the advance
     * afterwards uses the instance {@code clearCursor} returned. No exception, and the row ends
     * up advanced with the cursor still cleared (the reload produced no new cursor value).
     */
    @Test
    void advancingWithTheInstanceClearCursorReturnsCommitsCleanly() {
        SyncProgress progress = seededProgress(901L, "orders", "41");

        SyncProgress cleared = writer.clearCursor(progress);
        assertThat(cleared).isNotNull();

        assertThatCode(() -> writer.advanceCursor(cleared,
                new DataSyncService.TableSyncResult(),
                CursorStrategy.identity("id", Types.INTEGER, "test")))
                .doesNotThrowAnyException();

        SyncProgress row = reload(901L, "orders");
        assertThat(row.getLastSyncValue()).isNull();
        assertThat(row.getCursorColumn()).isEqualTo("id");
        assertThat(row.getCursorStrategy()).isEqualTo("IDENTITY");
        assertThat(row.getInitialLoadDone()).isTrue();
    }

    /**
     * The bug the return value exists to prevent: ignore what {@code clearCursor} saved,
     * advance with the instance the caller already held, and the optimistic lock rejects the
     * write. {@link SyncEngine} treats this as recoverable — reload the row, advance once more —
     * but the failure itself must stay loud enough for that path to trigger.
     */
    @Test
    void advancingWithTheCallersStaleInstanceFailsTheOptimisticLock() {
        SyncProgress progress = seededProgress(902L, "orders", "41");

        writer.clearCursor(progress); // return value deliberately ignored

        assertThatThrownBy(() -> writer.advanceCursor(progress,
                new DataSyncService.TableSyncResult(),
                CursorStrategy.identity("id", Types.INTEGER, "test")))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
