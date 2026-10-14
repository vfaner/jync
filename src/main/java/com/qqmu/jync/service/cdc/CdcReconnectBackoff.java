package com.qqmu.jync.service.cdc;

/** Exponential reconnect delay, 1s doubling up to 60s (task book B7). Single-threaded by contract. */
public final class CdcReconnectBackoff {

    private final long initialMs;
    private final long maxMs;
    private int attempt;

    public CdcReconnectBackoff(long initialMs, long maxMs) {
        this.initialMs = Math.max(1L, initialMs);
        this.maxMs = Math.max(this.initialMs, maxMs);
    }

    public long nextDelayMs() {
        long delay = Math.min(maxMs, initialMs * (1L << Math.min(attempt, 40)));
        attempt++;
        return delay;
    }

    public void reset() {
        attempt = 0;
    }

    /** Escalation step reached so far; package-private for tests (ruling R3b). */
    int attemptForTest() {
        return attempt;
    }

    /**
     * Per-project CDC idle-poll floor: a tighter loop than this hammers the source with
     * mining-session opens / slot peeks / change-table queries. Form validation and the
     * read-side clamp share this constant.
     */
    public static final long MIN_IDLE_POLL_MS = 50L;

    /**
     * Delay before a polling CDC engine's next cycle:
     * <ul>
     *   <li>the cycle failed → exponential reconnect backoff (never 0: a hot failure loop
     *       must be rate-limited even if rows were drained before the failure);</li>
     *   <li>success and rows were drained → 0: keep mining immediately while the stream is
     *       busy, matching Debezium's continuous-mining cadence and keeping peak latency to
     *       one cycle's mining cost instead of a fixed 1s wait;</li>
     *   <li>success and the stream was empty → the configured idle poll.</li>
     * </ul>
     * Busy and idle-success cycles do not consume backoff attempts; only failures do.
     */
    public static long nextCycleDelayMs(Throwable failure, boolean drainedAny,
                                        CdcReconnectBackoff backoff, long idlePollMs) {
        if (failure != null) {
            return backoff.nextDelayMs();
        }
        return drainedAny ? 0L : idlePollMs;
    }

    /**
     * Resolves the per-project idle-poll override against the engine default, applying
     * {@link #MIN_IDLE_POLL_MS} on the read side so neither the form nor a direct API call
     * can configure a source-killing tight loop.
     */
    public static long effectiveIdlePollMs(Long configured, long engineDefaultMs) {
        if (configured == null || configured <= 0L) {
            return engineDefaultMs;
        }
        return Math.max(MIN_IDLE_POLL_MS, configured);
    }
}
