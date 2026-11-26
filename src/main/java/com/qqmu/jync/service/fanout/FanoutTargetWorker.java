package com.qqmu.jync.service.fanout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * One target's consumer: buffers a source transaction's row events until its COMMIT, applies
 * them through {@link EventApplier}, then acknowledges. Each target owns an independent
 * position and failure state; a failure stops this worker only.
 */
@Slf4j
public final class FanoutTargetWorker implements AutoCloseable {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final EventTransport.Subscription subscription;
    private final EventApplier applier;
    private final Consumer<String> onCommit;
    private final Thread thread;
    private volatile boolean running = true;
    @Getter private volatile Throwable lastError;
    @Getter private volatile long appliedTransactions;
    @Getter private volatile String lastAppliedPosition;

    public FanoutTargetWorker(String name, EventTransport.Subscription subscription,
                              EventApplier applier) {
        this(name, subscription, applier, null);
    }

    public FanoutTargetWorker(String name, EventTransport.Subscription subscription,
                              EventApplier applier, Consumer<String> onCommit) {
        this.subscription = subscription;
        this.applier = applier;
        this.onCommit = onCommit;
        this.thread = new Thread(this::run, "fanout-" + name);
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public EventTransport.Subscription subscription() {
        return subscription;
    }

    public boolean isAlive() {
        return thread.isAlive();
    }

    private void run() {
        List<ChangeEvent> buffer = new ArrayList<>();
        String transactionId = null;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                ChangeEvent event = subscription.poll(POLL_TIMEOUT);
                if (event == null) {
                    continue;
                }
                switch (event.kind()) {
                    case ROW:
                        if (transactionId != null && !transactionId.equals(event.transactionId())) {
                            // The previous transaction never got its COMMIT: the capture
                            // died between the last ROW and the commit record, so that
                            // transaction was never acknowledged. Discard it and continue
                            // with the new one. The old code threw here, and the baseline
                            // that follows a restart appends new tokens to the same spool,
                            // wedging the target in a permanent restart loop.
                            log.warn("Fan-out target '{}': discarding {} uncommitted row(s)"
                                    + " of transaction {} (no COMMIT in the spool)",
                                    thread.getName(), buffer.size(), transactionId);
                            buffer = new ArrayList<>();
                        }
                        transactionId = event.transactionId();
                        buffer.add(event);
                        break;
                    case COMMIT:
                        if (transactionId != null && !transactionId.equals(event.transactionId())) {
                            throw new IllegalStateException("COMMIT for " + event.transactionId()
                                    + " while " + transactionId + " is open");
                        }
                        applier.applyTransaction(event.transactionId(), event.position(), buffer);
                        // Persist the target offset BEFORE acknowledging: if the offset
                        // write fails, the event stays in the spool and is replayed, not lost.
                        if (onCommit != null) {
                            onCommit.accept(event.position());
                        }
                        subscription.ack(event);
                        appliedTransactions++;
                        lastAppliedPosition = event.position();
                        buffer = new ArrayList<>();
                        transactionId = null;
                        break;
                    case DDL:
                        applier.applyDdl(event);
                        subscription.ack(event);
                        lastAppliedPosition = event.position();
                        break;
                    default:
                        break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            lastError = t;
            log.error("Fan-out target '{}' stopped: {}", thread.getName(), t.toString());
        } finally {
            try {
                applier.close();
            } catch (Exception e) {
                log.debug("Fan-out target applier close failed: {}", e.toString());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            subscription.close();
        } catch (Exception e) {
            log.debug("Fan-out subscription close failed: {}", e.toString());
        }
        thread.interrupt();
        try {
            thread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
