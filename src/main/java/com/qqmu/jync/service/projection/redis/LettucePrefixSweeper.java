package com.qqmu.jync.service.projection.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.util.CryptoUtil;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;

/**
 * The real prefix sweep: {@code SCAN MATCH <prefix>* COUNT 1000} in a loop, {@code UNLINK} in
 * batches of 500.
 *
 * <p>Never {@code KEYS}: it blocks the server for the whole keyspace and a shared cache is
 * exactly where that hurts. Never {@code FLUSHDB}/{@code FLUSHALL}: those belong to the server
 * operator, not to one project. A cluster target is swept one master at a time, because SCAN
 * only ever sees the node it runs on.
 */
@Component
public class LettucePrefixSweeper implements RedisPrefixSweeper {

    private static final int SCAN_COUNT = 1_000;
    private static final int UNLINK_BATCH = 500;

    private final Connector connector;

    @Autowired
    public LettucePrefixSweeper(CryptoUtil cryptoUtil) {
        this(cryptoUtil, new LettuceConnector(cryptoUtil));
    }

    /** The connector is a seam: tests hand in a fake, production builds Lettuce ones. */
    LettucePrefixSweeper(CryptoUtil cryptoUtil, Connector connector) {
        this.connector = connector;
    }

    @Override
    public long sweep(RedisTarget target, String prefix) {
        String base = prefix == null ? "" : prefix.trim();
        if (base.isEmpty()) {
            // An empty prefix would become "*" and delete every key in the database.
            throw new IllegalArgumentException("A Redis key prefix is required to sweep");
        }
        if (!base.endsWith(":")) {
            base += ":";
        }
        String pattern = base + "*";

        long unlinked = 0;
        // Close every handle after the whole sweep, never between masters: on a cluster the
        // handles are pins on one shared connection whose closer shuts the client down, so
        // closing the first one would fail every later master's SCAN.
        List<Handle> handles = connector.open(target);
        try {
            for (Handle handle : handles) {
                String cursor;
                do {
                    ScanPage page = scan(handle, pattern);
                    for (int from = 0; from < page.keys().size(); from += UNLINK_BATCH) {
                        List<String> batch = page.keys().subList(from,
                                Math.min(from + UNLINK_BATCH, page.keys().size()));
                        unlinked += unlink(handle, batch);
                    }
                    cursor = page.cursor();
                } while (!"0".equals(cursor));
            }
        } finally {
            for (Handle handle : handles) {
                try {
                    handle.close();
                } catch (RuntimeException ignored) {
                    // Best effort: the first close already shut the shared client down, and
                    // this must not hide the sweep's own exception.
                }
            }
        }
        return unlinked;
    }

    private static ScanPage scan(Handle handle, String pattern) {
        try {
            return handle.scan(pattern);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Redis prefix sweep scan failed: " + describe(e), e);
        }
    }

    private static long unlink(Handle handle, List<String> keys) {
        try {
            return handle.unlink(keys);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Redis prefix sweep unlink failed: " + describe(e), e);
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /** One master's connection; on a cluster the shared connection is closed by the first
     *  handle to be closed, and the rest are no-ops. */
    private static final class LettuceHandle implements Handle {

        private final RedisCommands<String, String> commands;
        private final Runnable closer;
        private final AtomicBoolean closed;
        private KeyScanCursor<String> cursor;

        private LettuceHandle(RedisCommands<String, String> commands, Runnable closer,
                              AtomicBoolean closed) {
            this.commands = commands;
            this.closer = closer;
            this.closed = closed;
        }

        @Override
        public ScanPage scan(String pattern) {
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(SCAN_COUNT);
            KeyScanCursor<String> page = cursor == null
                    ? commands.scan(args)
                    : commands.scan(cursor, args);
            cursor = page;
            return new ScanPage(page.isFinished() ? "0" : page.getCursor(), page.getKeys());
        }

        @Override
        public long unlink(List<String> keys) {
            if (keys.isEmpty()) {
                return 0;
            }
            Long removed = commands.unlink(keys.toArray(new String[0]));
            return removed == null ? 0 : removed;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closer.run();
            }
        }
    }

    /** Builds pooled-free, short-lived connections; sweeps are a one-off admin action. */
    private static final class LettuceConnector implements Connector {

        private final CryptoUtil cryptoUtil;

        private LettuceConnector(CryptoUtil cryptoUtil) {
            this.cryptoUtil = cryptoUtil;
        }

        @Override
        public List<Handle> open(RedisTarget target) {
            String password = target.getPasswordEnc() == null || target.getPasswordEnc().isEmpty()
                    ? null
                    : cryptoUtil.decrypt(target.getPasswordEnc());
            RedisURI uri = RedisUris.from(target, password);
            Duration timeout = Duration.ofMillis(
                    target.getCommandTimeoutMs() > 0 ? target.getCommandTimeoutMs() : 10_000);

            if (target.isClusterMode()) {
                RedisClusterClient client = RedisClusterClient.create(uri);
                try {
                    client.setOptions(LettuceTimeouts.cluster(timeout));
                    StatefulRedisClusterConnection<String, String> connection = client.connect();
                    AtomicBoolean closed = new AtomicBoolean();
                    Runnable close = () -> {
                        connection.close();
                        client.shutdown();
                    };
                    List<Handle> handles = new ArrayList<>();
                    for (RedisClusterNode node : connection.getPartitions()) {
                        if (!node.is(RedisClusterNode.NodeFlag.UPSTREAM)) {
                            continue;
                        }
                        StatefulRedisConnection<String, String> pinned =
                                connection.getConnection(node.getNodeId());
                        handles.add(new LettuceHandle(pinned.sync(), close, closed));
                    }
                    if (handles.isEmpty()) {
                        close.run();
                    }
                    return handles;
                } catch (RuntimeException e) {
                    client.shutdown();
                    throw e;
                }
            }

            RedisClient client = RedisClient.create(uri);
            try {
                client.setOptions(LettuceTimeouts.client(timeout));
                StatefulRedisConnection<String, String> connection = client.connect();
                return List.of(new LettuceHandle(connection.sync(), () -> {
                    connection.close();
                    client.shutdown();
                }, new AtomicBoolean()));
            } catch (RuntimeException e) {
                client.shutdown();
                throw e;
            }
        }
    }
}
