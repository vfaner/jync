package com.qqmu.jync.service.sync;

/**
 * Raised when a long-running load aborts at a safe boundary because a stop was requested —
 * either the thread was INTERRUPTED (CDC disable / rebaseline / node shutdown) or the
 * cycle's context saw the polling runner's stop signal (the UI's 「停止同步」). Neither is a
 * failure.
 *
 * <p>Like {@link LockLostException} it aborts the whole cycle rather than the current
 * table, and already-committed chunks stay (idempotent upserts, checkpoints kept, so the
 * load resumes where it stopped). Unlike a real failure it must not put an ERROR hat on
 * the task: {@code SyncEngine.runCycle} folds it into the result without an ERROR
 * change-log row, and the CDC baseline/consumer path reads the still-set interrupt flag
 * to skip {@code recordCdcFailure} and leave the baseline not-done, so the next start
 * redoes the full load. The polling runner records the result through
 * {@code recordStoppedOutcome}, which keeps the failure ladder out of a deliberate stop.
 */
public class StopRequestedException extends RuntimeException {

    public StopRequestedException(String message) {
        super(message);
    }
}
