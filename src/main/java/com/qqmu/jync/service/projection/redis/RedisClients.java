package com.qqmu.jync.service.projection.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.util.CryptoUtil;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;

/**
 * Keeps one reusable Lettuce connection per Redis target.
 *
 * <p>Connecting per CDC transaction would pay a handshake for every small commit; a CDC stream
 * commits constantly. The client and connection are therefore cached by target id and rebuilt when
 * the saved configuration changes ({@code updatedAt}). One connection serves one projection at a
 * time — {@link Holder#sink()} takes a lock — because auto-flush and {@code FLUSH} are
 * connection-wide; the contention it adds is irrelevant next to a per-transaction handshake.
 */
@Component
public class RedisClients implements AutoCloseable {

    private final CryptoUtil cryptoUtil;
    private final ConcurrentHashMap<Long, Holder> cache = new ConcurrentHashMap<>();

    public RedisClients(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    public RedisCommandSink sink(RedisTarget target) {
        return holder(target).sink();
    }

    /** Drops a target's cached connection, e.g. after its configuration was edited. */
    public void evict(Long targetId) {
        if (targetId == null) {
            return;
        }
        Holder holder = cache.remove(targetId);
        if (holder != null) {
            holder.close();
        }
    }

    @Override
    public void close() {
        cache.values().forEach(Holder::close);
        cache.clear();
    }

    private Holder holder(RedisTarget target) {
        if (target.getId() == null) {
            // A transient target (a probe) has no stable identity to cache against.
            return create(target);
        }
        long version = versionOf(target);
        return cache.compute(target.getId(), (id, existing) -> {
            if (existing != null && existing.version == version) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return create(target);
        });
    }

    private Holder create(RedisTarget target) {
        String password = target.getPasswordEnc() == null || target.getPasswordEnc().isEmpty()
                ? null
                : cryptoUtil.decrypt(target.getPasswordEnc());
        RedisURI uri = RedisUris.from(target, password);
        Duration timeout = Duration.ofMillis(
                target.getCommandTimeoutMs() > 0 ? target.getCommandTimeoutMs() : 10_000);
        long version = versionOf(target);

        if (target.isClusterMode()) {
            return openCluster(RedisClusterClient.create(uri), timeout, version);
        }
        return open(RedisClient.create(uri), timeout, version);
    }

    /**
     * Opens a standalone connection on an already-built client.
     *
     * <p>Package-private so a test can hand in a client whose {@code connect()} throws: the
     * client owns Netty resources, and a failed connect must shut it down or every failed CDC
     * transaction leaks an event loop (nothing is cached on failure, so the next attempt builds
     * another one).
     */
    Holder open(RedisClient client, Duration timeout, long version) {
        try {
            return connectStandalone(client, timeout, version);
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
    }

    private Holder connectStandalone(RedisClient client, Duration timeout, long version) {
        client.setDefaultTimeout(timeout);
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisAsyncCommands<String, String> async = connection.async();
        AsyncOps ops = new AsyncOps() {
            @Override
            public RedisFuture<String> set(String key, String value) {
                return async.set(key, value);
            }

            @Override
            public RedisFuture<Long> hset(String key, Map<String, String> fields) {
                return async.hset(key, fields);
            }

            @Override
            public RedisFuture<Long> hdel(String key, List<String> fields) {
                return async.hdel(key, fields.toArray(new String[0]));
            }

            @Override
            public RedisFuture<Long> del(String key) {
                return async.del(key);
            }

            @Override
            public RedisFuture<Boolean> expire(String key, long seconds) {
                return async.expire(key, seconds);
            }
        };
        return new Holder(connection, ops, timeout, version, client::shutdown);
    }

    /** Cluster counterpart of {@link #open(RedisClient, Duration, long)}. */
    Holder openCluster(RedisClusterClient client, Duration timeout, long version) {
        try {
            return connectCluster(client, timeout, version);
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
    }

    private Holder connectCluster(RedisClusterClient client, Duration timeout, long version) {
        client.setDefaultTimeout(timeout);
        StatefulRedisClusterConnection<String, String> connection = client.connect();
        RedisClusterAsyncCommands<String, String> async = connection.async();
        AsyncOps ops = new AsyncOps() {
            @Override
            public RedisFuture<String> set(String key, String value) {
                return async.set(key, value);
            }

            @Override
            public RedisFuture<Long> hset(String key, Map<String, String> fields) {
                return async.hset(key, fields);
            }

            @Override
            public RedisFuture<Long> hdel(String key, List<String> fields) {
                return async.hdel(key, fields.toArray(new String[0]));
            }

            @Override
            public RedisFuture<Long> del(String key) {
                return async.del(key);
            }

            @Override
            public RedisFuture<Boolean> expire(String key, long seconds) {
                return async.expire(key, seconds);
            }
        };
        return new Holder(connection, ops, timeout, version, client::shutdown);
    }

    private static long versionOf(RedisTarget target) {
        return target.getUpdatedAt() == null ? 0L : target.getUpdatedAt().toEpochMilli();
    }

    /** The command surface this package needs; both standalone and cluster connections provide it. */
    private interface AsyncOps {
        RedisFuture<String> set(String key, String value);

        RedisFuture<Long> hset(String key, Map<String, String> fields);

        RedisFuture<Long> hdel(String key, List<String> fields);

        RedisFuture<Long> del(String key);

        RedisFuture<Boolean> expire(String key, long seconds);
    }

    private static final class Holder {

        private final StatefulConnection<String, String> connection;
        private final AsyncOps ops;
        private final Duration timeout;
        private final long version;
        private final Runnable closer;
        private final ReentrantLock lock = new ReentrantLock();
        private boolean closed;

        private Holder(StatefulConnection<String, String> connection, AsyncOps ops,
                       Duration timeout, long version, Runnable closer) {
            this.connection = connection;
            this.ops = ops;
            this.timeout = timeout;
            this.version = version;
            this.closer = closer;
        }

        private RedisCommandSink sink() {
            lock.lock();
            if (closed) {
                lock.unlock();
                throw new IllegalStateException("Redis connection is closed");
            }
            try {
                connection.setAutoFlushCommands(false);
            } catch (RuntimeException e) {
                lock.unlock();
                throw e;
            }
            return new LettuceSink(this);
        }

        private void close() {
            lock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                connection.close();
                closer.run();
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class LettuceSink implements RedisCommandSink {

        private final Holder holder;
        private final List<RedisFuture<?>> pending = new ArrayList<>();

        private LettuceSink(Holder holder) {
            this.holder = holder;
        }

        @Override
        public void set(String key, String value) {
            pending.add(holder.ops.set(key, value));
        }

        @Override
        public void hset(String key, Map<String, String> fields) {
            pending.add(holder.ops.hset(key, fields));
        }

        @Override
        public void hdel(String key, List<String> fields) {
            pending.add(holder.ops.hdel(key, fields));
        }

        @Override
        public void del(String key) {
            pending.add(holder.ops.del(key));
        }

        @Override
        public void expire(String key, long ttlSeconds) {
            pending.add(holder.ops.expire(key, ttlSeconds));
        }

        @Override
        public void flush() throws Exception {
            RedisFuture<?>[] futures = pending.toArray(new RedisFuture<?>[0]);
            holder.connection.flushCommands();
            long timeoutMs = holder.timeout.toMillis();
            if (!LettuceFutures.awaitAll(timeoutMs, TimeUnit.MILLISECONDS, futures)) {
                throw new RedisProjectionException(
                        "Redis pipeline timed out after " + timeoutMs + " ms");
            }
            for (RedisFuture<?> future : futures) {
                // Reading every reply is the point: a WRONGTYPE or OOM response only surfaces
                // from get(), and a failed pipeline must not let the consumer advance.
                future.get();
            }
        }

        @Override
        public void close() {
            pending.clear();
            try {
                holder.connection.setAutoFlushCommands(true);
            } finally {
                holder.lock.unlock();
            }
        }
    }
}
