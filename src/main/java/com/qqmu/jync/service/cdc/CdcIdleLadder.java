package com.qqmu.jync.service.cdc;

/**
 * Idle-poll ladder for session-per-cycle CDC engines (Oracle / DM LogMiner).
 *
 * <p>Unlike a streaming engine, LogMiner cannot keep a cursor open across idle periods:
 * every cycle re-runs ADD_LOGFILE → START_LOGMNR → cursor → END_LOGMNR. Polling a quiet
 * project at the configured interval therefore costs a full mining round (dictionary and
 * archive-logfile work) per second on the source, forever. This ladder doubles the wait
 * after each empty healthy cycle up to a ceiling, and drops straight back to the
 * configured poll as soon as a cycle applied rows — so a busy stream still continues
 * immediately (delay 0) and a project that goes from quiet to active is noticed within
 * one cycle, not after the whole ladder.
 *
 * <p>Single-threaded by contract (the consumer loop), like {@link CdcReconnectBackoff}.
 */
final class CdcIdleLadder {

    /** Longest idle wait the ladder escalates to when the configured poll is shorter. */
    static final long DEFAULT_CEILING_MS = 5_000L;

    private final long baseMs;
    private final long ceilingMs;
    private long currentMs;

    CdcIdleLadder(long baseMs, long ceilingMs) {
        this.baseMs = Math.max(CdcReconnectBackoff.MIN_IDLE_POLL_MS, baseMs);
        this.ceilingMs = Math.max(this.baseMs, ceilingMs);
        this.currentMs = this.baseMs;
    }

    /**
     * Wait before the next cycle, for a healthy cycle that drained {@code drainedAny} rows:
     * zero on activity (and the ladder returns to its base for the next quiet cycle), the
     * current ladder step on an empty cycle — escalating one step for the cycle after it.
     */
    long nextDelayMs(boolean drainedAny) {
        if (drainedAny) {
            currentMs = baseMs;
            return 0L;
        }
        long delay = currentMs;
        currentMs = Math.min(ceilingMs, currentMs * 2L);
        return delay;
    }

    /** A failed cycle is governed by the reconnect backoff; the ladder must not stay escalated. */
    void reset() {
        currentMs = baseMs;
    }
}
