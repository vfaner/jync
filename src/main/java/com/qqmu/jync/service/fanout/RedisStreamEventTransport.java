package com.qqmu.jync.service.fanout;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStreamCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import lombok.extern.slf4j.Slf4j;

/**
 * Optional fan-out transport backed by a Redis Stream.
 *
 * <p>One stream per project; one consumer group per target. The capture appends without
 * waiting for a subscriber, so a slow or dead target no longer backpressures the others —
 * the same isolation the disk spool gives, without sharing a filesystem between nodes.
 *
 * <p>Delivery is at-least-once: an event is read with {@code XREADGROUP}, applied by the
 * target worker, and only then {@code XACK}ed. A worker restart re-reads this consumer's
 * pending entries ({@code XREADGROUP ... 0}) before new ones, which is exactly what
 * {@link Subscription#rewindToAck()} asks for. The consumer name is derived from the group
 * (that is, the target), so a restarted process finds its own pending list.
 *
 * <p>The stream is not trimmed by default: trimming could drop an event no target has
 * acknowledged yet, which is data loss. Operators who cap it knowingly trade that safety
 * for disk, and {@code XINFO STREAM} is their monitoring hook.
 */
@Slf4j
public final class RedisStreamEventTransport implements EventTransport {

    private static final RedisCodec<String, byte[]> CODEC =
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    /** Events handed to one poll from Redis at a time; the rest stay on the server. */
    private static final int READ_BATCH = 16;

    private final RedisClient client;
    private final String streamKey;
    private final long maxLen;
    /** Set after an XTRIM failure so an unsupported command cannot fail every publish. */
    private volatile boolean trimDisabled;
    private final StatefulRedisConnection<String, byte[]> publisher;
    private final Object publishLock = new Object();
    private final List<Sub> subscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public RedisStreamEventTransport(RedisURI uri, String streamKey) {
        this(uri, streamKey, 0L);
    }

    public RedisStreamEventTransport(RedisURI uri, String streamKey, long maxLen) {
        this.client = RedisClient.create(uri);
        this.maxLen = maxLen;
        // A blocking XREADGROUP parks the command for the poll timeout; the command timeout
        // has to sit above it or an idle stream would be cancelled as a failure.
        this.client.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(30)))
                .build());
        this.publisher = client.connect(CODEC);
        this.streamKey = streamKey;
    }

    @Override
    public Subscription subscribe(String consumerGroup) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        Sub sub = new Sub(consumerGroup == null ? "default" : consumerGroup);
        subscriptions.add(sub);
        return sub;
    }

    @Override
    public void publish(ChangeEvent event) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        byte[] bytes = EventCodec.serialize(event);
        synchronized (publishLock) {
            publisher.sync().xadd(streamKey, Map.of("e", bytes));
            boolean baselineEvent = event.transactionId() != null
                    && event.transactionId().startsWith("baseline");
            if (maxLen > 0L && !trimDisabled && !baselineEvent) {
                try {
                    // Approximate trimming is deliberate: exact MAXLEN is O(stream) and
                    // Redis recommends ~. 0 means the operator chose never to trim.
                    publisher.sync().xtrim(streamKey, true, maxLen);
                } catch (RuntimeException e) {
                    // Old Redis or a command error must not turn every publish into a
                    // failure; stop trimming and keep the transport alive.
                    trimDisabled = true;
                    log.warn("Redis Stream trimming is disabled after a failure on {}: {}",
                            streamKey, e.toString());
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Sub sub : subscriptions) {
            sub.close();
        }
        subscriptions.clear();
        publisher.close();
        client.shutdown();
    }

    private final class Sub implements Subscription {
        private final String group;
        private final String consumerName;
        private final StatefulRedisConnection<String, byte[]> connection;
        private final Deque<StreamMessage<String, byte[]>> buffer = new ArrayDeque<>();
        /**
         * Entry ids handed to the worker since the last successful transaction. The worker
         * acknowledges once per COMMIT (or DDL), and on the spool that offset covers the
         * rows before it; a Redis PEL is per entry, so every delivered row id is kept here
         * and acknowledged together with the commit. Without this the row entries stayed
         * pending forever and the PEL grew without bound.
         */
        private final List<String> deliveredIds = new java.util.ArrayList<>();
        /**
         * Pending-entry drain. A restart starts on the PEL (the previous process may have
         * read rows without committing them); {@code pendingCursor} advances past every
         * batch so a transaction larger than READ_BATCH entries is not re-read from the
         * same first entries forever.
         */
        private boolean rewindPending = true;
        private String pendingCursor = "0";
        private volatile boolean closed;

        Sub(String group) {
            this.group = group;
            this.consumerName = "target-" + group;
            this.connection = client.connect(CODEC);
            try {
                // A fresh group starts at 0: everything still in the stream is replayed, and
                // re-applying rows is idempotent. Starting at "$" would silently skip events
                // published before a restart that lost the group.
                connection.sync().xgroupCreate(
                        XReadArgs.StreamOffset.from(streamKey, "0"), group,
                        XGroupCreateArgs.Builder.mkstream());
            } catch (RedisBusyException e) {
                // The group already exists — its pending list is the resume point.
            }
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
            if (buffer.isEmpty()) {
                fill(timeout);
            }
            if (buffer.isEmpty()) {
                return null;
            }
            StreamMessage<String, byte[]> message = buffer.poll();
            byte[] payload = message.getBody().get("e");
            if (payload == null) {
                // Foreign entry (another producer on this key); drop it rather than stall.
                log.warn("Fan-out Redis stream {} has an entry without an 'e' field;"
                        + " acknowledging and skipping {}", streamKey, message.getId());
                ackIds(List.of(message.getId()));
                return null;
            }
            try {
                ChangeEvent event = EventCodec.deserialize(payload);
                deliveredIds.add(message.getId());
                return event;
            } catch (IOException e) {
                // Never ack an entry this process cannot read: skipping it would apply a
                // partial transaction (the COMMIT still arrives) and lose rows silently.
                // The worker stops with its pending list intact, so nothing is lost.
                throw new IllegalStateException("fan-out Redis stream entry "
                        + message.getId() + " cannot be decoded", e);
            }
        }

        private void fill(Duration timeout) throws InterruptedException {
            RedisStreamCommands<String, byte[]> commands = connection.sync();
            if (rewindPending) {
                List<StreamMessage<String, byte[]>> pending = commands.xreadgroup(
                        Consumer.from(group, consumerName),
                        XReadArgs.Builder.count(READ_BATCH),
                        XReadArgs.StreamOffset.from(streamKey, pendingCursor));
                if (pending != null && !pending.isEmpty()) {
                    buffer.addAll(pending);
                    pendingCursor = pending.get(pending.size() - 1).getId();
                    if (pending.size() < READ_BATCH) {
                        // This was the tail of the PEL; switch to new entries after it is
                        // consumed. An exactly-full batch is followed by one more read from
                        // the advanced cursor, which then returns the tail (or nothing).
                        rewindPending = false;
                    }
                    return;
                }
                rewindPending = false;
            }
            List<StreamMessage<String, byte[]>> fresh = commands.xreadgroup(
                    Consumer.from(group, consumerName),
                    XReadArgs.Builder.count(READ_BATCH)
                            .block(Math.max(1L, timeout.toMillis())),
                    XReadArgs.StreamOffset.lastConsumed(streamKey));
            if (fresh != null && !fresh.isEmpty()) {
                buffer.addAll(fresh);
            }
        }

        private void ackIds(List<String> ids) {
            if (ids.isEmpty()) {
                return;
            }
            try {
                connection.sync().xack(streamKey, group, ids.toArray(new String[0]));
            } catch (RuntimeException e) {
                // The entries stay pending; a restart replays them (idempotent) — never a loss.
                log.debug("Fan-out Redis XACK {} failed: {}", ids, e.toString());
            }
        }

        @Override
        public void ack(ChangeEvent event) {
            List<String> ids = new java.util.ArrayList<>(deliveredIds);
            deliveredIds.clear();
            ackIds(ids);
        }

        @Override
        public void rewindToAck() {
            buffer.clear();
            deliveredIds.clear();
            pendingCursor = "0";
            rewindPending = true;
        }

        @Override
        public void close() {
            closed = true;
            try {
                connection.close();
            } catch (RuntimeException e) {
                log.debug("Fan-out Redis subscription close failed: {}", e.toString());
            }
        }
    }
}
