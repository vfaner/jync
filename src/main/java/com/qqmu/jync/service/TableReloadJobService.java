package com.qqmu.jync.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.service.task.SyncTaskRunner;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a single-table reload outside the browser request. A large table can take minutes;
 * holding the HTTP request (and its servlet thread) open for that long is the same problem
 * {@link RowCountJobService} solves for exact counts.
 *
 * <p>The job itself is deliberately thin: validation, locking and the actual load stay in
 * {@link TableMaintenanceService} / {@link SyncTaskRunner}. This service only adds a stable
 * job id, a pollable status and a cancel path. A cancel asks the running cycle to stop at its
 * next committed batch; a job that has not started yet is cancelled without touching data.
 */
@Service
@Slf4j
public class TableReloadJobService {

    private final TableMaintenanceService tableMaintenance;
    private final SyncTaskRunner taskRunner;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "jync-table-reload");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public TableReloadJobService(TableMaintenanceService tableMaintenance,
                                 SyncTaskRunner taskRunner) {
        this.tableMaintenance = tableMaintenance;
        this.taskRunner = taskRunner;
    }

    /** Starts a job, or returns the id of an already-running reload of the same table. */
    public String submit(Long projectId, String table) {
        purgeOld();
        for (Job job : jobs.values()) {
            if (job.projectId.equals(projectId) && job.table.equals(table) && job.isActive()) {
                return job.id;
            }
        }
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, projectId, table);
        jobs.put(id, job);
        job.future = executor.submit(() -> run(job));
        return id;
    }

    public Optional<JobView> find(Long projectId, String jobId) {
        purgeOld();
        Job job = jobs.get(jobId);
        return job == null || !job.projectId.equals(projectId)
                ? Optional.empty() : Optional.of(job.view());
    }

    /** Cancels a queued/running reload. Returns false when the job is unknown or finished. */
    public boolean cancel(Long projectId, String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !job.projectId.equals(projectId)) {
            return false;
        }
        return job.cancel(taskRunner);
    }

    private void run(Job job) {
        if (job.cancelRequested) {
            job.finishCancelled();
            return;
        }
        long started = System.currentTimeMillis();
        try {
            SyncTaskRunner.Outcome outcome = tableMaintenance.reload(job.projectId, job.table);
            long elapsed = System.currentTimeMillis() - started;
            switch (outcome.kind()) {
                case BUSY -> job.finishError("error.table.reload.busy", elapsed);
                case NO_PROJECT -> job.finishError("error.project.not.found", elapsed);
                default -> {
                    SyncResult result = outcome.result().orElse(null);
                    if (result == null) {
                        job.finishError("error.table.reload.failed", elapsed);
                    } else if (result.isStopped()) {
                        job.finishCancelled();
                    } else if (result.isSuccess()) {
                        job.finishDone(result, elapsed);
                    } else {
                        job.finishError(result.getErrors().isEmpty()
                                ? "error.table.reload.failed"
                                : String.join("; ", result.getErrors()), elapsed);
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            job.finishError(e.getMessage() == null
                    ? "error.table.reload.failed" : e.getMessage(),
                    System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            log.warn("Table reload job {} for project {}, table {} failed: {}",
                    job.id, job.projectId, job.table, e.toString());
            job.finishError("error.table.reload.failed",
                    System.currentTimeMillis() - started);
        }
    }

    private void purgeOld() {
        Instant cutoff = Instant.now().minusSeconds(30 * 60);
        jobs.entrySet().removeIf(entry -> entry.getValue().createdAt.isBefore(cutoff)
                && !entry.getValue().isActive());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public enum Status { RUNNING, CANCELLING, DONE, ERROR, CANCELLED }

    public record JobView(String id, Long projectId, String table, Status status,
                          String message, String summary, List<String> errors,
                          Long rowsInserted, Long rowsUpdated, Long durationMs) {
    }

    private static final class Job {
        private final String id;
        private final Long projectId;
        private final String table;
        private final Instant createdAt = Instant.now();
        private volatile Status status = Status.RUNNING;
        private volatile boolean cancelRequested;
        private volatile String message;
        private volatile String summary;
        private volatile List<String> errors = List.of();
        private volatile Long rowsInserted;
        private volatile Long rowsUpdated;
        private volatile Long durationMs;
        private volatile Future<?> future;

        private Job(String id, Long projectId, String table) {
            this.id = id;
            this.projectId = projectId;
            this.table = table;
        }

        private synchronized boolean cancel(SyncTaskRunner taskRunner) {
            if (status != Status.RUNNING) {
                return false;
            }
            cancelRequested = true;
            Future<?> queued = future;
            if (queued != null && queued.cancel(false)) {
                status = Status.CANCELLED;
                return true;
            }
            // Already executing. The cycle stops at its next committed batch; the job stays
            // CANCELLING until that happens.
            status = Status.CANCELLING;
            taskRunner.requestStop(projectId);
            return true;
        }

        private boolean isActive() {
            return status == Status.RUNNING || status == Status.CANCELLING;
        }

        private synchronized void finishDone(SyncResult result, long elapsed) {
            if (status == Status.CANCELLED) {
                return;
            }
            status = Status.DONE;
            summary = result.summary();
            errors = List.copyOf(result.getErrors());
            rowsInserted = result.getRowsInserted();
            rowsUpdated = result.getRowsUpdated();
            durationMs = elapsed;
        }

        private synchronized void finishError(String failure, long elapsed) {
            if (status == Status.CANCELLED) {
                return;
            }
            status = Status.ERROR;
            message = failure;
            durationMs = elapsed;
        }

        private synchronized void finishCancelled() {
            status = Status.CANCELLED;
        }

        private JobView view() {
            return new JobView(id, projectId, table, status, message, summary,
                    errors, rowsInserted, rowsUpdated, durationMs);
        }
    }
}
