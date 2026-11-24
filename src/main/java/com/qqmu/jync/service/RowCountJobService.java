package com.qqmu.jync.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

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

    /** Exact scans are deliberately serialized per side by the caller; two workers are enough. */
    private static final int EXACT_COUNT_TIMEOUT_SECONDS = 300;

    private final TablePreviewService preview;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "jync-row-count");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public RowCountJobService(TablePreviewService preview) {
        this.preview = preview;
    }

    public String submit(Long projectId, String table) {
        purgeOld();
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, projectId, table);
        jobs.put(id, job);
        executor.submit(() -> run(job));
        return id;
    }

    public Optional<JobView> find(Long projectId, String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !job.projectId.equals(projectId)) {
            return Optional.empty();
        }
        return Optional.of(job.view());
    }

    private void run(Job job) {
        try {
            TableRowCount result = preview.rowCount(job.projectId, job.table, true,
                    EXACT_COUNT_TIMEOUT_SECONDS);
            job.finish(result, null);
        } catch (RuntimeException e) {
            log.warn("Exact row-count job {} for {}.{} failed: {}",
                    job.id, job.projectId, job.table, e.getMessage());
            job.finish(null, e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
        }
    }

    private void purgeOld() {
        Instant cutoff = Instant.now().minusSeconds(30 * 60);
        jobs.entrySet().removeIf(entry -> entry.getValue().createdAt.isBefore(cutoff));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public enum Status { RUNNING, DONE, ERROR }

    public record JobView(String id, Long projectId, String table, Status status,
                          TableRowCount rowCount, String error) {
    }

    private static final class Job {
        private final String id;
        private final Long projectId;
        private final String table;
        private final Instant createdAt = Instant.now();
        private volatile Status status = Status.RUNNING;
        private volatile TableRowCount rowCount;
        private volatile String error;

        private Job(String id, Long projectId, String table) {
            this.id = id;
            this.projectId = projectId;
            this.table = table;
        }

        private void finish(TableRowCount result, String failure) {
            rowCount = result;
            error = failure;
            status = failure == null ? Status.DONE : Status.ERROR;
        }

        private JobView view() {
            return new JobView(id, projectId, table, status, rowCount, error);
        }
    }
}
