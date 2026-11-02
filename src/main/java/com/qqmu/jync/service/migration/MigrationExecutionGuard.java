package com.qqmu.jync.service.migration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * One in-process slot per migration task, plus the cancellation token belonging to that run.
 *
 * <p>Deliberately in-memory, like {@code EtlExecutionGuard}: a dedicated migration is an
 * operator-started batch, and running the same task on two nodes is not coordinated. The
 * visible partial target index lets an operator re-run with {@code OVERWRITE} to converge.
 */
@Component
public class MigrationExecutionGuard {

    private final Map<Long, MigrationCancellation> running = new ConcurrentHashMap<>();

    /** Claims the task's slot and creates the token its engine will check. */
    public boolean tryAcquire(Long taskId) {
        return running.putIfAbsent(taskId, new MigrationCancellation()) == null;
    }

    public void release(Long taskId) {
        running.remove(taskId);
    }

    public boolean isRunning(Long taskId) {
        return running.containsKey(taskId);
    }

    /** Returns the token for a running task, or null when nothing holds the slot. */
    public MigrationCancellation cancellation(Long taskId) {
        return running.get(taskId);
    }

    /** Returns false when the task is not running; the scheduler then has nothing to stop. */
    public boolean cancel(Long taskId) {
        MigrationCancellation token = running.get(taskId);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }
}
