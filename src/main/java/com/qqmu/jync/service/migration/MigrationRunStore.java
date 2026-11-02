package com.qqmu.jync.service.migration;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
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

    @Transactional
    public void finish(Long runId, Long taskId, MigrationRunResult result) {
        MigrationRunStatus status = statusOf(result);
        Instant finishedAt = Instant.now();
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
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setLastRunTime(finishedAt);
            task.setLastRunSuccess(status == MigrationRunStatus.SUCCESS);
            task.setLastRunResult(summary(status, result));
            taskRepository.save(task);
        });
    }

    /** Records a crash the engine could not describe itself. */
    @Transactional
    public void fail(Long runId, Long taskId, String message) {
        Instant finishedAt = Instant.now();
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(MigrationRunStatus.FAILED);
            run.setFinishedAt(finishedAt);
            run.setMessage(message);
            runRepository.save(run);
        });
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setLastRunTime(finishedAt);
            task.setLastRunSuccess(false);
            task.setLastRunResult("FAILED " + message);
            taskRepository.save(task);
        });
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
