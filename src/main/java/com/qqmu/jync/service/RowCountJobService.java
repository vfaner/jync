package com.qqmu.jync.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.TableRowCount;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs exact row-count scans outside the browser request. A five-hundred-million-row table
 * can take minutes; holding an HTTP request (and its servlet thread) open for that long is
 * what made the old 15-second exact path unusable on large databases.
 */
@Service
@Slf4j
public class RowCountJobService {

    private final TablePreviewService preview;
    private final SyncProperties properties;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "jync-row-count");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public RowCountJobService(TablePreviewService preview, SyncProperties properties) {
        this.preview = preview;
        this.properties = properties;
    }

    public String submit(Long projectId, String table) {
        purgeOld();
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, projectId, table);
        jobs.put(id, job);
        job.future = executor.submit(() -> run(job));
        return id;
    }

    public Optional<JobView> find(Long projectId, String jobId) {
        purgeOld();
        Job job = jobs.get(jobId);
        if (job == null || !job.projectId.equals(projectId)) {
            return Optional.empty();
        }
        return Optional.of(job.view());
    }

    /** Marks a running scan cancelled and interrupts its worker. */
    public boolean cancel(Long projectId, String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !job.projectId.equals(projectId)) {
            return false;
        }
        return job.cancel();
    }

    private void run(Job job) {
        if (job.isCancelled()) {
            return;
        }
        try {
            int timeout = Math.max(1, properties.getRowCountJobTimeoutSeconds());
            TableRowCount result = preview.rowCount(job.projectId, job.table, true, timeout,
                    job.control);
            job.finish(result, null);
        } catch (RuntimeException e) {
            if (job.isCancelled()) {
                return;
            }
            log.warn("Exact row-count job {} for {}.{} failed: {}",
                    job.id, job.projectId, job.table, e.getMessage());
            job.finish(null, e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
        }
    }

    private void purgeOld() {
        Instant cutoff = Instant.now().minusSeconds(30 * 60);
        jobs.entrySet().removeIf(entry -> entry.getValue().createdAt.isBefore(cutoff)
                && !entry.getValue().isRunning());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public enum Status { RUNNING, DONE, ERROR, CANCELLED }

    public record JobView(String id, Long projectId, String table, Status status,
                          TableRowCount rowCount, String error) {
    }

    private static final class Job {
        private final String id;
        private final Long projectId;
        private final String table;
        private final Instant createdAt = Instant.now();
        private final RowCountControl control = new RowCountControl();
        private volatile Status status = Status.RUNNING;
        private volatile TableRowCount rowCount;
        private volatile String error;
        private volatile Future<?> future;

        private Job(String id, Long projectId, String table) {
            this.id = id;
            this.projectId = projectId;
            this.table = table;
        }

        private synchronized boolean cancel() {
            if (status != Status.RUNNING) {
                return false;
            }
            status = Status.CANCELLED;
            // The statement cancel is what actually stops a server-side scan; the thread
            // interrupt alone is advisory and JDBC drivers may ignore it.
            control.cancel();
            Future<?> running = future;
            if (running != null) {
                running.cancel(true);
            }
            return true;
        }

        private synchronized void finish(TableRowCount result, String failure) {
            if (status == Status.CANCELLED) {
                return;
            }
            rowCount = result;
            error = failure;
            status = failure == null ? Status.DONE : Status.ERROR;
        }

        private boolean isCancelled() {
            return status == Status.CANCELLED;
        }

        private boolean isRunning() {
            return status == Status.RUNNING;
        }

        private JobView view() {
            return new JobView(id, projectId, table, status, rowCount, error);
        }
    }
}
