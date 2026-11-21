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
 * <p>The execution guard lives in memory, so nothing can still be running after a restart —
 * <b>on this node</b>. In a multi-node deployment another node may be executing one of these
 * runs right now; marking it INTERRUPTED would rewrite a live run as failed and freeze its
 * progress counter (progress only updates RUNNING rows). Each run records the node that
 * started it ({@code run_node}); rows owned by another node are left alone. Rows without an
 * owner predate the column and are treated as this node's, which is exactly the old
 * single-node behaviour.
 */
@Component
public class MigrationRunRecovery {

    private final MigrationRunRepository runRepository;
    private final MigrationTaskRepository taskRepository;
    private final MigrationNodeId nodeId;

    public MigrationRunRecovery(MigrationRunRepository runRepository,
                               MigrationTaskRepository taskRepository,
                               MigrationNodeId nodeId) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.nodeId = nodeId;
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
            if (run.getRunNode() != null && !run.getRunNode().equals(nodeId.id())) {
                continue;   // another live node owns it
            }
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
