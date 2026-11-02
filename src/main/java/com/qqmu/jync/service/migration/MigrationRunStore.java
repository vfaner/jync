package com.qqmu.jync.service.migration;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;

/**
 * Bookkeeping for one run: the row is written before the engine starts, updated while it works
 * and given its terminal state when it ends.
 *
 * <p>The task row only carries the latest summary; every attempt leaves a run row behind even
 * when its task is deleted later.
 */
@Service
public class MigrationRunStore {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunStore.class);

    private final MigrationRunRepository runRepository;
    private final MigrationTaskRepository taskRepository;

    public MigrationRunStore(MigrationRunRepository runRepository,
                             MigrationTaskRepository taskRepository) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public MigrationRun start(Long taskId, MigrationRunTrigger trigger) {
        MigrationRun run = new MigrationRun();
        run.setMigrationTaskId(taskId);
        run.setStatus(MigrationRunStatus.RUNNING);
        run.setTriggerType(trigger);
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    /** Best-effort progress; the run's own row is the only thing this may touch. */
    @Transactional
    public void progress(Long runId, String currentIndex, long documentsRead,
                         long documentsWritten) {
        runRepository.findById(runId).ifPresent(run -> {
            if (run.getStatus() != MigrationRunStatus.RUNNING) {
                return;
            }
            run.setCurrentIndex(currentIndex);
            run.setDocumentsRead(documentsRead);
            run.setDocumentsWritten(documentsWritten);
            runRepository.save(run);
        });
    }

    public void finish(Long runId, Long taskId, MigrationRunResult result) {
        MigrationRunStatus status = statusOf(result);
        Instant finishedAt = Instant.now();
        // The run row is the record of truth and is written first, in its own transaction. The
        // task summary below is a convenience for the list badge: a concurrent edit bumps the
        // task's @Version, and it must not roll the run row back — that would let the crash
        // handler rewrite a completed migration as FAILED and drop its counters.
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setFinishedAt(finishedAt);
            run.setIndicesTotal(result.indicesTotal());
            run.setIndicesSucceeded(result.indicesSucceeded());
            run.setIndicesFailed(result.indicesFailed());
            run.setDocumentsRead(result.documentsRead());
            run.setDocumentsWritten(result.documentsWritten());
            run.setMessage(result.message());
            runRepository.save(run);
        });
        updateTaskSummary(taskId, finishedAt, status == MigrationRunStatus.SUCCESS,
                summary(status, result));
    }

    /**
     * Records a crash the engine could not describe itself.
     *
     * <p>The run row keeps {@code message}, which may be an i18n key so the history can show it
     * in the viewer's language. The task summary is plain text rendered directly in the list
     * badge, so it gets {@code summary} instead of the key.
     */
    public void fail(Long runId, Long taskId, String message, String summary) {
        Instant finishedAt = Instant.now();
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(MigrationRunStatus.FAILED);
            run.setFinishedAt(finishedAt);
            run.setMessage(message);
            runRepository.save(run);
        });
        updateTaskSummary(taskId, finishedAt, false,
                summary == null || summary.isBlank() ? "FAILED" : "FAILED " + summary);
    }

    /**
     * Writes the latest-run summary onto the task row, retrying once with a freshly loaded row.
     * If the concurrent edit still wins, the summary is skipped: the run row already holds the
     * outcome, and failing the whole bookkeeping here would turn a finished run into a crash.
     */
    private void updateTaskSummary(Long taskId, Instant finishedAt, boolean success,
                                   String latestSummary) {
        for (int attempt = 0; attempt < 2; attempt++) {
            MigrationTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            task.setLastRunTime(finishedAt);
            task.setLastRunSuccess(success);
            task.setLastRunResult(latestSummary);
            try {
                taskRepository.save(task);
                return;
            } catch (OptimisticLockingFailureException e) {
                // Someone edited or toggled the task while this run was finishing; re-read and
                // try once more, which is what keeps their edit and this summary both intact.
            }
        }
        log.warn("Could not update the last-run summary of migration task {}: the row kept "
                + "changing while the run finished; the run history keeps the outcome", taskId);
    }

    private static MigrationRunStatus statusOf(MigrationRunResult result) {
        if (result.cancelled()) {
            return MigrationRunStatus.CANCELLED;
        }
        return result.success() ? MigrationRunStatus.SUCCESS : MigrationRunStatus.FAILED;
    }

    private static String summary(MigrationRunStatus status, MigrationRunResult result) {
        StringBuilder text = new StringBuilder(status.name())
                .append(" indices=").append(result.indicesSucceeded())
                .append('/').append(result.indicesTotal())
                .append(" read=").append(result.documentsRead())
                .append(" written=").append(result.documentsWritten());
        if (result.message() != null && !result.message().isBlank()) {
            text.append(" message=").append(result.message());
        }
        return text.toString();
    }
}
