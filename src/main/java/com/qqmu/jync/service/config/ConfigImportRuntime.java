package com.qqmu.jync.service.config;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.etl.EtlScheduler;
import com.qqmu.jync.service.migration.MigrationScheduler;
import com.qqmu.jync.service.task.ScheduleReconciler;

import lombok.extern.slf4j.Slf4j;

/**
 * Puts a running process back in step with the configuration a package just wrote.
 *
 * <p>An import replaces every row the page shows; nothing in memory knows that. Pooled
 * connections and cached health verdicts are keyed by the old ids and would be handed to the
 * new databases; scheduled projects, ETL tasks and migration tasks do not exist until someone
 * asks the repositories. Starting the process again does all of this — this class is the same
 * work without the restart.
 *
 * <p>Caches are cleared first: a CDC consumer started by the reconciler must not pick up a pool
 * that still points at the previous database behind the reused id.
 *
 * <p>Every step is isolated. The import has already committed, so a failure here has nothing to
 * roll back; letting one broken cron expression abort the rest would leave a perfectly good
 * configuration half-armed. Each failure is logged and the next step runs.
 */
@Slf4j
@Component
public class ConfigImportRuntime {

    private final DataSourceManager dataSources;
    private final DatabaseHealthService health;
    private final ScheduleReconciler reconciler;
    private final EtlScheduler etlScheduler;
    private final MigrationScheduler migrationScheduler;

    public ConfigImportRuntime(DataSourceManager dataSources,
                               DatabaseHealthService health,
                               ScheduleReconciler reconciler,
                               EtlScheduler etlScheduler,
                               MigrationScheduler migrationScheduler) {
        this.dataSources = dataSources;
        this.health = health;
        this.reconciler = reconciler;
        this.etlScheduler = etlScheduler;
        this.migrationScheduler = migrationScheduler;
    }

    public void rearm() {
        step("data-source pool", dataSources::evictAll);
        step("database health cache", health::evictAll);
        step("sync schedules", reconciler::reconcile);
        step("ETL schedules", etlScheduler::scheduleAllOnStartup);
        step("migration schedules", migrationScheduler::scheduleAllOnStartup);
    }

    private static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Already committed: log and keep going, the next step may still succeed.
            log.warn("Configuration import: refreshing {} failed: {}", name, e.toString());
        }
    }
}
