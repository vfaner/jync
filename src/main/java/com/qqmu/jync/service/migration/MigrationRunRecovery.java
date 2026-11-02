package com.qqmu.jync.service.migration;

import java.time.Instant;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;

/**
 * Startup repair for runs the previous process left {@code RUNNING}.
 *
 * <p>The execution guard lives in memory, so nothing can still be running after a restart.
 * Marking the rows {@code INTERRUPTED} keeps the history honest instead of leaving runs that
 * look in flight forever.
 */
@Component
public class MigrationRunRecovery {

    private final MigrationRunRepository runRepository;
    private final MigrationTaskRepository taskRepository;

    public MigrationRunRecovery(MigrationRunRepository runRepository,
                               MigrationTaskRepository taskRepository) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void onStartup() {
        recoverInterrupted();
    }

    /** Returns how many runs were repaired; exposed so tests can assert the work. */
    public int recoverInterrupted() {
        int recovered = 0;
        for (MigrationRun run : runRepository.findByStatus(MigrationRunStatus.RUNNING)) {
            Instant finishedAt = Instant.now();
            run.setStatus(MigrationRunStatus.INTERRUPTED);
            run.setFinishedAt(finishedAt);
            run.setMessage("error.migration.interrupted");
            runRepository.save(run);

            taskRepository.findById(run.getMigrationTaskId()).ifPresent(task -> {
                task.setLastRunTime(finishedAt);
                task.setLastRunSuccess(false);
                task.setLastRunResult("INTERRUPTED");
                taskRepository.save(task);
            });
            recovered++;
        }
        return recovered;
    }
}
