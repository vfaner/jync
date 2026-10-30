package com.qqmu.jync.service.connection;

/**
 * Raised when a cycle cannot borrow its connections because another task's workers hold
 * every seat in the shared pool — not because the database is unreachable.
 *
 * <p>It is a queue signal, not a fault: the run was deferred, nothing was attempted, and
 * the next scheduled fire tries again. {@code SyncEngine} folds it into the result as
 * {@code deferred}, and {@code SyncTaskRunner} records no outcome for it, so the task row
 * keeps its previous state instead of claiming "No changes" for a look never taken.
 */
public class PoolContentionException extends RuntimeException {

    public PoolContentionException(String message) {
        super(message);
    }
}
