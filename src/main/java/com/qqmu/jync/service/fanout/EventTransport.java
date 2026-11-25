package com.qqmu.jync.service.fanout;

import java.time.Duration;

/**
 * Fan-out transport: one capture publishes every event to every target subscription.
 *
 * <p>The first implementation is in-process; a disk-backed/Redis Stream transport can
 * implement the same contract later without touching the target workers.
 */
public interface EventTransport extends AutoCloseable {

    Subscription subscribe(String consumerGroup);

    void publish(ChangeEvent event) throws InterruptedException;

    interface Subscription extends AutoCloseable {
        String group();

        /** @return the next event, or null when the timeout elapsed or the transport closed */
        ChangeEvent poll(Duration timeout) throws InterruptedException;

        /** Acknowledges an event for this group; the PoC transport uses it for lag accounting. */
        void ack(ChangeEvent event);

        /**
         * Rewinds this subscription to its last acknowledged offset. Used when a target worker
         * is replaced: events delivered but not yet committed must be replayed, not skipped.
         */
        default void rewindToAck() {
        }
    }
}
