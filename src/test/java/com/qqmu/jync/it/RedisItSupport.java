package com.qqmu.jync.it;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;

import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.repository.RedisTargetRepository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Shared real Redis 7 target for the projection IT family.
 *
 * <p>Mirrors {@link CdcMySqlContainerHarnessIT}'s conventions: ONE container, started lazily on
 * first use, deliberately NOT {@code @Container}-managed (Ryuk reaps it at JVM exit). Every
 * assertion goes through raw Lettuce commands — never through Jync's own serializers — because
 * the point of these tests is that a plain Redis client, like the application that will read
 * the cache, sees the expected bytes.
 *
 * <p>{@link #stopContainer()} / {@link #startContainer()} restart the SAME container with
 * docker stop/start, not a fresh one: the mapped host port survives a restart, which is what
 * lets a saved {@code RedisTarget} keep pointing at it while the CDC consumer rides out the
 * outage and replays.
 */
final class RedisItSupport {

    static final int PORT = 6379;

    private static GenericContainer<?> shared;
    private static int mappedPort = -1;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;

    private RedisItSupport() {
    }

    static synchronized GenericContainer<?> container() {
        if (shared == null) {
            GenericContainer<?> redis = new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(PORT)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(2)));
            redis.start();
            shared = redis;
        }
        return shared;
    }

    static String host() {
        return container().getHost();
    }

    /**
     * The port the container listens on right now.
     *
     * <p>Deliberately not {@code getMappedPort}: Testcontainers caches the inspect response
     * taken at startup, and Docker re-resolves a randomly assigned host port at every start —
     * so after {@link #stopContainer()}/{@link #startContainer} the cached value is the port
     * that is no longer bound. A stopped container reports no binding at all, hence the cached
     * fallback for the outage window.
     */
    static int port() {
        Ports.Binding[] bindings = container().getCurrentContainerInfo().getNetworkSettings()
                .getPorts().getBindings().get(new ExposedPort(PORT));
        if (bindings != null && bindings.length > 0 && bindings[0] != null
                && bindings[0].getHostPortSpec() != null) {
            mappedPort = Integer.parseInt(bindings[0].getHostPortSpec());
        }
        return mappedPort;
    }

    static String uri() {
        return "redis://" + host() + ":" + port();
    }

    /**
     * A saved target row pointing at the shared container. Short timeouts so an outage surfaces
     * in a second instead of the production ten, and a per-call unique name for the entity's
     * unique constraint.
     */
    static RedisTarget newTarget(RedisTargetRepository targets, String tag) {
        RedisTarget target = new RedisTarget();
        target.setName("it-redis-" + tag + "-" + System.nanoTime());
        target.setHost(host());
        target.setPort(port());
        target.setConnectTimeoutMs(1_000);
        target.setCommandTimeoutMs(1_000);
        return targets.save(target);
    }

    /** Raw GET; {@code null} when the key is absent or Redis is unreachable. */
    static synchronized String get(String key) {
        try {
            return connection().sync().get(key);
        } catch (RuntimeException e) {
            dropConnection();
            return null;
        }
    }

    /** Raw SET through a plain client, used to plant keys the projection must (not) touch. */
    static synchronized void set(String key, String value) {
        try {
            connection().sync().set(key, value);
        } catch (RuntimeException e) {
            dropConnection();
            throw e;
        }
    }

    /** Raw PING through a throwaway client; false when the server does not answer quickly. */
    static boolean pings() {
        RedisClient probe = RedisClient.create(RedisURI.builder()
                .withHost(host())
                .withPort(port())
                .withTimeout(Duration.ofMillis(700))
                .build());
        try (StatefulRedisConnection<String, String> conn = probe.connect()) {
            return "PONG".equals(conn.sync().ping());
        } catch (RuntimeException e) {
            return false;
        } finally {
            probe.shutdown();
        }
    }

    /** docker stop (SIGTERM), keeping the container and its port binding for {@link #startContainer()}. */
    static void stopContainer() {
        closeCachedClient();
        GenericContainer<?> redis = container();
        redis.getDockerClient().stopContainerCmd(redis.getContainerId())
                .withTimeout(10)
                .exec();
    }

    /**
     * docker start of the stopped container, then repoints the saved target at the port the
     * restarted container came up on. Docker resolves a randomly assigned host port at every
     * start, so a restart moves it — the equivalent of an operator rerouting the cache while
     * the consumer waits it out. The saved row's {@code updatedAt} bump is also what makes
     * {@code RedisClients} build a fresh connection instead of reusing the dead one.
     */
    static void startContainer(RedisTargetRepository targets, Long targetId) {
        GenericContainer<?> redis = container();
        redis.getDockerClient().startContainerCmd(redis.getContainerId()).exec();
        closeCachedClient();
        waitFor("Redis to answer PING after restart", 30_000, RedisItSupport::pings);
        RedisTarget fresh = targets.findById(targetId)
                .orElseThrow(() -> new AssertionError("Redis target " + targetId + " vanished"));
        fresh.setPort(port());
        targets.save(fresh);
    }

    static void waitFor(String what, long timeoutMs, BooleanSupplier condition) {
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainingMs <= 0) {
                throw new AssertionError("Timed out after " + timeoutMs + " ms waiting for: " + what);
            }
            try {
                Thread.sleep(Math.min(200L, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
    }

    private static StatefulRedisConnection<String, String> connection() {
        if (connection == null || !connection.isOpen()) {
            dropConnection();
            client = RedisClient.create(uri());
            client.setDefaultTimeout(Duration.ofSeconds(5));
            connection = client.connect();
        }
        return connection;
    }

    private static void closeCachedClient() {
        dropConnection();
    }

    private static void dropConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // Already broken; the next call builds a fresh client.
            }
            connection = null;
        }
        if (client != null) {
            try {
                client.shutdown();
            } catch (RuntimeException ignored) {
                // Same: a broken client must not mask the caller's outcome.
            }
            client = null;
        }
    }
}
