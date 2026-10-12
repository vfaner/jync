package com.qqmu.jync.service.cdc;

/**
 * Lifecycle handle of one long-lived CDC consumer thread (v1.6.0 extraction).
 *
 * <p>The manager owns threads per project and must stop/join them on demand (strategy switch,
 * disable, shutdown). v1.5.0 narrowed that handle to the binlog-only {@code CdcConsumer};
 * v1.6.0 adds a second implementation ({@link LogMinerCdcConsumer}), so the shared contract is
 * pulled up: a consumer is a {@link Runnable} the manager starts on its own thread plus a
 * {@link #requestStop()} handle that must unblock the loop cleanly (no failure recorded, the
 * in-flight transaction replays after the next start).
 */
public interface ManagedCdcConsumer extends Runnable {

    /**
     * Signals the consumer to exit. The loop must break through the QUIET path — no ERROR
     * hat, no {@code recordCdcFailure} — because the caller (manager stop / strategy switch /
     * shutdown) already owns the task state. The currently buffered transaction replays
     * after the next start (at-least-once, zero loss).
     */
    void requestStop();
}
