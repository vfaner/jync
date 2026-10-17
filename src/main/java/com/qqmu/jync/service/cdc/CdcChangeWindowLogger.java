package com.qqmu.jync.service.cdc;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.cdc.CdcRuntimeStats.TableChange;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns the CDC pipeline's per-table change windows into aggregated change-log entries.
 *
 * <p>CDC is a continuous stream, not a run-per-cycle job, so per-row logging would flood
 * the change log at peak and per-cycle logging does not exist for the push-based binlog
 * engine. Instead counts accumulate per table in {@link CdcRuntimeStats}; one shared daemon
 * scheduler ticks once a second per JVM and flushes windows that have been open for the
 * configured age (default 10s) as a single {@code DATA/INFO} entry carrying the upsert and
 * delete counts plus the watermark reached. On consumer {@link #close()} every undued window
 * is flushed as well, so a quiet stream never strands counts past shutdown.
 *
 * <p>A flush that carried rows also advances the project's 上次同步: the windows only
 * aggregate rows the applier already committed, so a non-empty window is the CDC pipeline's
 * only evidence that data landed (the polling engine has {@code SyncTaskStore.recordOutcome}
 * for the same job). The timestamp is advanced even if the change-log write itself fails —
 * the audit entry is the side effect, the data landing is not.
 *
 * <p>Failures of the meta-database write are swallowed with a warn: this is an observability
 * side path and must never stop or park a consumer thread.
 */
@Slf4j
public final class CdcChangeWindowLogger implements AutoCloseable {

    /** Single shared ticker for all projects: each tick is one cheap map age scan. */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "jync-cdc-window-log");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    static final long DEFAULT_TICK_MS = 1_000L;

    private final long projectId;
    private final CdcRuntimeStats stats;
    private final SyncStateWriter stateWriter;
    private final SyncTaskStore taskStore;
    private final long windowMs;
    private final long tickMs;
    private final LongSupplier clockMillis;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ScheduledFuture<?> task;

    public CdcChangeWindowLogger(long projectId, CdcRuntimeStats stats,
                                 SyncStateWriter stateWriter, SyncTaskStore taskStore,
                                 long windowMs) {
        this(projectId, stats, stateWriter, taskStore, windowMs,
                DEFAULT_TICK_MS, System::currentTimeMillis);
    }

    CdcChangeWindowLogger(long projectId, CdcRuntimeStats stats, SyncStateWriter stateWriter,
                          SyncTaskStore taskStore, long windowMs, long tickMs,
                          LongSupplier clockMillis) {
        this.projectId = projectId;
        this.stats = stats;
        this.stateWriter = stateWriter;
        this.taskStore = taskStore;
        this.windowMs = windowMs;
        this.tickMs = tickMs;
        this.clockMillis = clockMillis;
    }

    /** Begins the periodic flush. A non-positive {@code windowMs} disables the feature. */
    public void start() {
        if (closed.get() || windowMs <= 0) {
            return;
        }
        task = SCHEDULER.scheduleWithFixedDelay(this::tickSafely,
                tickMs, tickMs, TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        try {
            flushDue();
        } catch (Throwable t) {
            // scheduleWithFixedDelay silently cancels a task that throws — never let that happen.
            log.warn("CDC change-window flush for project {} failed: {}",
                    projectId, t.toString());
        }
    }

    /** Flushes windows old enough; package-private so tests can drive it without the scheduler. */
    void flushDue() {
        write(stats.drainDueWindows(windowMs, clockMillis.getAsLong()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> current = task;
        if (current != null) {
            current.cancel(false);
        }
        // Final handoff regardless of window age: counts already applied must not disappear
        // from the log just because the consumer stopped inside a quiet period.
        write(stats.drainAllWindows());
    }

    private void write(List<TableChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        String through = stats.positionLabel();
        boolean applied = false;
        for (TableChange change : changes) {
            long total = change.upserts() + change.deletes();
            applied |= total > 0;
            String detail = "CDC window: " + change.upserts() + " upserts, "
                    + change.deletes() + " deletes"
                    + (through != null ? " (through " + through + ")" : "");
            try {
                stateWriter.recordLog(projectId, ObjectType.DATA, change.table(),
                        ChangeType.INFO, detail, true, 0L,
                        (int) Math.min(Integer.MAX_VALUE, total));
            } catch (RuntimeException e) {
                log.warn("Could not write CDC change window for project {} table {}: {}",
                        projectId, change.table(), e.getMessage());
            }
        }
        // One stamp per flush, not per table: the dashboard cares when data last landed,
        // and every table in this batch landed at essentially the same moment.
        if (applied) {
            try {
                taskStore.markCdcDataApplied(projectId);
            } catch (RuntimeException e) {
                log.warn("Could not advance last-sync for CDC project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }
}
