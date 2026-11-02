package com.qqmu.jync.service.migration;

import java.time.Instant;

/**
 * Cooperative cancellation for one run.
 *
 * <p>A manual cancel or the task's timeout sets a flag; the engine checks it between scroll
 * pages and bulk batches. No thread is interrupted, so a bulk request already in flight is
 * allowed to finish.
 */
public final class MigrationCancellation {

    private volatile boolean cancelled;
    private volatile Instant deadline;

    public void cancel() {
        this.cancelled = true;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public boolean isCancelled() {
        Instant limit = deadline;
        return cancelled || (limit != null && !Instant.now().isBefore(limit));
    }

    public void check() {
        if (isCancelled()) {
            throw new MigrationCancelledException();
        }
    }
}
