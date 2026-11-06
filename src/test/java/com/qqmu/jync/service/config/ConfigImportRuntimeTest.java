package com.qqmu.jync.service.config;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.etl.EtlScheduler;
import com.qqmu.jync.service.migration.MigrationScheduler;
import com.qqmu.jync.service.task.ScheduleReconciler;

/**
 * What happens after a configuration import has committed.
 *
 * <p>The import writes rows, but nothing in the running process knows about them: pooled
 * connections and cached health verdicts still point at the ids that were just replaced, and no
 * scheduler has ever seen the imported projects or tasks. The order matters — caches first, so
 * the schedulers that start a CDC consumer do not reuse a pool for a database that no longer
 * exists. And a scheduler that throws cannot undo a committed import, so each step is isolated.
 */
class ConfigImportRuntimeTest {

    private final DataSourceManager dataSources = mock(DataSourceManager.class);
    private final DatabaseHealthService health = mock(DatabaseHealthService.class);
    private final ScheduleReconciler reconciler = mock(ScheduleReconciler.class);
    private final EtlScheduler etlScheduler = mock(EtlScheduler.class);
    private final MigrationScheduler migrationScheduler = mock(MigrationScheduler.class);

    private ConfigImportRuntime runtime() {
        return new ConfigImportRuntime(dataSources, health, reconciler, etlScheduler,
                migrationScheduler);
    }

    @Test
    void cachesAreClearedBeforeAnythingIsScheduled() {
        runtime().rearm();

        InOrder order = inOrder(dataSources, health, reconciler, etlScheduler, migrationScheduler);
        order.verify(dataSources).evictAll();
        order.verify(health).evictAll();
        order.verify(reconciler).reconcile();
        order.verify(etlScheduler).scheduleAllOnStartup();
        order.verify(migrationScheduler).scheduleAllOnStartup();
    }

    @Test
    void oneFailingSchedulerDoesNotStopTheRest() {
        doThrow(new IllegalStateException("cron parse failed"))
                .when(etlScheduler).scheduleAllOnStartup();

        runtime().rearm();

        // The import is already committed; refusing to start the remaining schedules would
        // leave a working configuration half-armed.
        verify(migrationScheduler).scheduleAllOnStartup();
        verify(reconciler).reconcile();
    }

    @Test
    void aFailingCacheEvictionDoesNotStopTheRest() {
        doThrow(new IllegalStateException("pool close failed")).when(dataSources).evictAll();

        runtime().rearm();

        verify(health).evictAll();
        verify(reconciler).reconcile();
    }
}
