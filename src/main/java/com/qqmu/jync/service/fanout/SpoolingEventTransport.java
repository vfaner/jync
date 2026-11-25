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
        byte[] bytes = EventCodec.serialize(event);
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
        private long ackPosition;
        private volatile boolean closed;

        SpoolSubscription(String group) throws IOException {
            this.group = group;
            this.logPath = directory.resolve(group + ".log");
            this.offsetPath = directory.resolve(group + ".offset");
            this.spool = new RandomAccessFile(logPath.toFile(), "rw");
            long ack = readOffset();
            this.ackPosition = Math.min(ack, spool.length());
            this.readPosition = ackPosition;
            truncateIncompleteTail();
        }

        /**
         * A crash between the 4-byte length and the payload leaves a truncated record at the
         * end of the file. Reading it again after restart fails forever (the supervisor
         * restarts the worker onto the same bytes), so the tail is cut back to the last
         * complete record when the spool is opened.
         */
        private void truncateIncompleteTail() throws IOException {
            long length = spool.length();
            long pos = ackPosition;
            while (pos + 4L <= length) {
                spool.seek(pos);
                int size = spool.readInt();
                if (size <= 0 || size > 64 * 1024 * 1024) {
                    break;
                }
                long end = pos + 4L + size;
                if (end > length) {
                    break;
                }
                pos = end;
            }
            if (pos < length) {
                log.warn("Truncating {} byte(s) of an incomplete fan-out record at the tail"
                        + " of {}", length - pos, logPath);
                spool.setLength(pos);
            }
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
                if (size + 4L + bytes.length > maxBytesPerTarget) {
                    throw new IllegalStateException("fan-out spool for target '" + group
                            + "' reached its " + maxBytesPerTarget + "-byte cap; the target is"
                            + " too far behind (or dead) — fix it, then restart the group");
                }
                spool.seek(size);
                spool.writeInt(bytes.length);
                spool.write(bytes);
                // Wake a poller parked on the end of the file; without this every target
                // worker would busy-spin at EOF instead of sleeping between records.
                notifyAll();
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
                int length;
                try {
                    length = spool.readInt();
                } catch (java.io.EOFException eof) {
                    waitForData(timeout);
                    return null;
                }
                if (length <= 0 || length > 64 * 1024 * 1024) {
                    throw new IOException("invalid fan-out record length " + length);
                }
                byte[] bytes = new byte[length];
                long recordStart = readPosition;
                try {
                    spool.readFully(bytes);
                } catch (java.io.EOFException eof) {
                    // The writer has not finished this record yet (or died mid-write and the
                    // tail was not cut at open). Do not consume it: poll again later.
                    spool.seek(recordStart);
                    waitForData(timeout);
                    return null;
                }
                readPosition = spool.getFilePointer();
                return EventCodec.deserialize(bytes);
            } catch (IOException e) {
                throw new IllegalStateException("could not read the fan-out spool for '"
                        + group, e);
            }
        }

        /** Park on the subscription until an append notifies or the poll timeout elapses. */
        private void waitForData(Duration timeout) throws InterruptedException {
            long millis = timeout == null ? 0L : timeout.toMillis();
            if (millis > 0L) {
                wait(millis);
            }
        }

        @Override
        public synchronized void ack(ChangeEvent event) {
            if (event == null) {
                return;
            }
            ackPosition = readPosition;
            try {
                Files.writeString(offsetPath, Long.toString(ackPosition));
                // Caught up: the whole file is acknowledged, so truncating it cannot drop
                // unread events and keeps a long-running spool from growing forever.
                if (ackPosition == spool.length() && ackPosition > 0) {
                    spool.setLength(0);
                    readPosition = 0;
                    ackPosition = 0;
                    Files.writeString(offsetPath, "0");
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not persist the fan-out offset for '"
                        + group, e);
            }
        }

        @Override
        public synchronized void rewindToAck() {
            readPosition = ackPosition;
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
