package com.qqmu.jync.service.fanout;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Disk-backed fan-out transport: every target has an append-only JSON-lines spool and its own
 * acknowledged offset. The capture appends without waiting for a consumer, so a slow target
 * no longer blocks the others; the per-target byte cap is the safety valve that turns a
 * permanently dead target into a visible error instead of a full disk.
 *
 * <p>The spool is a transport, not a system of record: a crash may replay from the last
 * acknowledged offset, which is safe because target writes are idempotent upserts. Compaction
 * (rewriting the file after the offset moves) is left for the operational phase; the cap and
 * monitoring make a runaway file visible first.
 */
@Slf4j
public final class SpoolingEventTransport implements EventTransport {

    private final Path directory;
    private final long maxBytesPerTarget;
    private final ObjectMapper mapper;
    private final List<SpoolSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public SpoolingEventTransport(Path directory, long maxBytesPerTarget, ObjectMapper mapper)
            throws IOException {
        this.directory = directory;
        this.maxBytesPerTarget = maxBytesPerTarget;
        this.mapper = mapper;
        Files.createDirectories(directory);
    }

    @Override
    public Subscription subscribe(String consumerGroup) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        try {
            SpoolSubscription sub = new SpoolSubscription(consumerGroup == null
                    ? "default" : consumerGroup);
            subscriptions.add(sub);
            return sub;
        } catch (IOException e) {
            throw new IllegalStateException("could not open the fan-out spool for "
                    + consumerGroup, e);
        }
    }

    @Override
    public void publish(ChangeEvent event) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        String line;
        try {
            line = mapper.writeValueAsString(event) + "\n";
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not serialize a fan-out event", e);
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        for (SpoolSubscription sub : subscriptions) {
            if (!sub.closed) {
                sub.append(bytes);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (SpoolSubscription sub : subscriptions) {
            sub.close();
        }
        subscriptions.clear();
    }

    private final class SpoolSubscription implements Subscription {
        private final String group;
        private final Path logPath;
        private final Path offsetPath;
        private final RandomAccessFile spool;
        private long readPosition;
        private volatile boolean closed;

        SpoolSubscription(String group) throws IOException {
            this.group = group;
            this.logPath = directory.resolve(group + ".log");
            this.offsetPath = directory.resolve(group + ".offset");
            this.spool = new RandomAccessFile(logPath.toFile(), "rw");
            long ack = readOffset();
            this.readPosition = Math.min(ack, spool.length());
        }

        private long readOffset() {
            try {
                if (!Files.exists(offsetPath)) {
                    return 0L;
                }
                return Long.parseLong(Files.readString(offsetPath).trim());
            } catch (IOException | NumberFormatException e) {
                log.warn("Fan-out spool offset '{}' unreadable; replaying from the start: {}",
                        group, e.toString());
                return 0L;
            }
        }

        synchronized void append(byte[] bytes) {
            if (closed) {
                throw new IllegalStateException("subscription '" + group + "' is closed");
            }
            try {
                long size = spool.length();
                if (size + bytes.length > maxBytesPerTarget) {
                    throw new IllegalStateException("fan-out spool for target '" + group
                            + "' reached its " + maxBytesPerTarget + "-byte cap; the target is"
                            + " too far behind (or dead) — fix it, then restart the group");
                }
                spool.seek(size);
                spool.write(bytes);
            } catch (IOException e) {
                throw new IllegalStateException("could not append to the fan-out spool for '"
                        + group, e);
            }
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public synchronized ChangeEvent poll(Duration timeout) throws InterruptedException {
            if (closed) {
                return null;
            }
            try {
                spool.seek(readPosition);
                String line = spool.readLine();
                if (line == null || line.isEmpty()) {
                    return null;
                }
                readPosition = spool.getFilePointer();
                return mapper.readValue(new String(line.getBytes(StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8), ChangeEvent.class);
            } catch (IOException e) {
                throw new IllegalStateException("could not read the fan-out spool for '"
                        + group, e);
            }
        }

        @Override
        public synchronized void ack(ChangeEvent event) {
            if (event == null) {
                return;
            }
            try {
                Files.writeString(offsetPath, Long.toString(readPosition));
                // Caught up: the whole file is acknowledged, so truncating it cannot drop
                // unread events and keeps a long-running spool from growing forever.
                if (readPosition == spool.length() && readPosition > 0) {
                    spool.setLength(0);
                    readPosition = 0;
                    Files.writeString(offsetPath, "0");
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not persist the fan-out offset for '"
                        + group, e);
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            try {
                spool.close();
            } catch (IOException e) {
                log.debug("Fan-out spool close failed for '{}': {}", group, e.toString());
            }
        }
    }
}
