package com.qqmu.jync.service.fanout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process fan-out used by the first PoC. Every target gets its own bounded queue; a
 * full queue blocks {@link #publish}, which is deliberate backpressure: the capture must
 * not run ahead of the slowest target beyond the bound. Disk spill / per-target isolation
 * (so one slow target does not block the others) is the next milestone, not this class.
 */
public final class InMemoryEventTransport implements EventTransport {

    private final int capacity;
    private final List<Sub> subscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public InMemoryEventTransport(int capacityPerTarget) {
        if (capacityPerTarget <= 0) {
            throw new IllegalArgumentException("capacityPerTarget must be positive");
        }
        this.capacity = capacityPerTarget;
    }

    @Override
    public Subscription subscribe(String consumerGroup) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        Sub sub = new Sub(consumerGroup == null ? "default" : consumerGroup, capacity);
        subscriptions.add(sub);
        return sub;
    }

    @Override
    public void publish(ChangeEvent event) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        // Same order for every target: publish sequentially, blocking on a full queue.
        for (Sub sub : subscriptions) {
            sub.offer(event);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Sub sub : subscriptions) {
            sub.close();
        }
        subscriptions.clear();
    }

    private static final class Sub implements Subscription {
        private final String group;
        private final BlockingQueue<ChangeEvent> queue;
        private final AtomicLong acked = new AtomicLong();
        private volatile boolean closed;

        Sub(String group, int capacity) {
            this.group = group;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        void offer(ChangeEvent event) throws InterruptedException {
            // A closed target must unblock a capture waiting on it instead of deadlocking.
            while (!closed) {
                if (queue.offer(event, 200L, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            throw new IllegalStateException("subscription '" + group + "' is closed");
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public ChangeEvent poll(Duration timeout) throws InterruptedException {
            if (closed) {
                return null;
            }
            ChangeEvent event = queue.poll(Math.max(0L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return event;
        }

        @Override
        public void ack(ChangeEvent event) {
            if (event != null) {
                acked.incrementAndGet();
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        long lag() {
            return queue.size();
        }
    }
}
