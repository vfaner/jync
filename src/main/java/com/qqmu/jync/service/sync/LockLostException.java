package com.qqmu.jync.service.sync;

/**
 * Raised when a sync lock lease cannot be renewed mid-run.
 *
 * <p>The previous owner is no longer guaranteed exclusive access — the lease expired and may
 * already have been granted to another instance — so any further writes risk two engines
 * mutating one target. Unlike a normal failure this must abort the whole cycle, not just the
 * current table. Already-committed batches stay (they are idempotent and will simply replay
 * to a no-op); the remaining work is picked up by whichever instance holds the lock next.
 */
public class LockLostException extends RuntimeException {

    public LockLostException(String message) {
        super(message);
    }
}
