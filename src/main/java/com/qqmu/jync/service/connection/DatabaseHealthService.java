package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.util.JdbcUtil;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * One health dot per connection on the connections page.
 *
 * <p>Every rule here exists to keep the check cheaper than the thing it reports on: one
 * one-connection pool per database, at most two databases dialled at the same time, a few
 * seconds each, and a cache so a page refresh — or a second tab — never doubles the load. A
 * failure is an answer too, and it is cached like one.
 *
 * <p>Nothing here is shared with the sync engine: no cached pool, no scheduler, no metadata
 * store. The only resource a check can compete for is the database's own connection slots,
 * and it gives that one back before returning.
 */
@Service
@Slf4j
public class DatabaseHealthService {

    /** The whole page gives up after this, however many databases are listed. */
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(8);

    /** How long a verdict stands before the next page load re-checks. */
    static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

    /** Never dial more than this many production databases at once. */
    private static final int MAX_PARALLEL = 2;

    /** Seconds handed to {@link Connection#isValid(int)}. */
    private static final int VALIDATION_SECONDS = 2;

    /** Message key the page localises; driver text is shown verbatim instead. */
    static final String TIMEOUT_MESSAGE = "error.db.health.timeout";
    /** The driver accepted the connection but {@code isValid} rejected it. */
    static final String INVALID_MESSAGE = "error.db.health.invalid";

    private final DataSourceManager dataSourceManager;
    private final MongoHealthProbe mongoProbe;
    private final Duration budget;
    private final Duration cacheTtl;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    /** Two constructors: this one is what Spring wires; the other exists for the tests. */
    @Autowired
    public DatabaseHealthService(DataSourceManager dataSourceManager,
                                 MongoHealthProbe mongoProbe) {
        this(dataSourceManager, mongoProbe, DEFAULT_BUDGET, DEFAULT_CACHE_TTL);
    }

    DatabaseHealthService(DataSourceManager dataSourceManager, MongoHealthProbe mongoProbe,
                          Duration budget, Duration cacheTtl) {
        this.dataSourceManager = dataSourceManager;
        this.mongoProbe = mongoProbe;
        this.budget = budget;
        this.cacheTtl = cacheTtl;
    }

    /** One row's verdict. {@code message} is null when healthy. */
    public record Health(Long id, boolean ok, long elapsedMs, String message) {
    }

    private record Cached(Health health, Instant expiresAt, Instant configUpdatedAt) {
    }

    /**
     * Drops every cached verdict.
     *
     * <p>Called after a configuration import: the ids in the cache now name different databases,
     * and the timestamp check that invalidates an edited row cannot see a row that was replaced
     * wholesale.
     */
    public void evictAll() {
        cache.clear();
    }

    /**
     * Checks the given connections, in the order given.
     *
     * @param force bypass the cache; the page's "check all" button sets this, an automatic
     *              page load does not
     */
    public List<Health> check(List<DatabaseConfig> configs, boolean force) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        Map<Long, Health> results = new LinkedHashMap<>();
        List<DatabaseConfig> pending = new ArrayList<>();
        for (DatabaseConfig config : configs) {
            Cached cached = force ? null : cache.get(config.getId());
            // An edited connection keeps its id: the verdict from before the edit says
            // nothing about the new host or password, so it must not be served.
            if (cached != null && cached.expiresAt().isAfter(now)
                    && Objects.equals(cached.configUpdatedAt(), config.getUpdatedAt())) {
                results.put(config.getId(), cached.health());
            } else {
                pending.add(config);
            }
        }
        if (!pending.isEmpty()) {
            probeAll(pending, results);
        }
        List<Health> ordered = new ArrayList<>(configs.size());
        for (DatabaseConfig config : configs) {
            ordered.add(results.get(config.getId()));
        }
        return ordered;
    }

    private void probeAll(List<DatabaseConfig> pending, Map<Long, Health> results) {
        // A pool per request, gone when the request is: a long-lived executor would keep
        // threads alive for a page nobody is looking at.
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(MAX_PARALLEL, pending.size()), daemonThreads());
        try {
            List<Future<Health>> futures = new ArrayList<>(pending.size());
            for (DatabaseConfig config : pending) {
                futures.add(executor.submit(() -> probe(config)));
            }
            long deadline = System.nanoTime() + budget.toNanos();
            Instant expiresAt = Instant.now().plus(cacheTtl);
            boolean interrupted = false;
            for (int i = 0; i < futures.size(); i++) {
                DatabaseConfig config = pending.get(i);
                Health health;
                if (interrupted) {
                    futures.get(i).cancel(false);
                    health = timeout(config, 0);
                } else {
                    health = await(futures.get(i), config, deadline);
                    interrupted = Thread.currentThread().isInterrupted();
                }
                results.put(config.getId(), health);
                cache.put(config.getId(),
                        new Cached(health, expiresAt, config.getUpdatedAt()));
            }
        } finally {
            // Abandoned probes are cancelled without an interrupt, so they run to completion on
            // daemon threads and close their pools themselves; shutdown lets them finish.
            executor.shutdown();
        }
    }

    /**
     * Waits for one probe until the page's shared deadline, then abandons it.
     *
     * <p>The abandonment must not interrupt the worker. A probe that is already closing its
     * Hikari pool is inside {@code HikariPool.shutdown()}, waiting for the pool's own executors
     * to terminate; interrupting it there makes Hikari treat the close as a shutdown request —
     * it throws at the first {@code awaitTermination}, skips closing the pool's connections,
     * and logs "Interrupted during closing" with a stack that reads like a failure. A probe
     * that is still dialling is bounded by Hikari's own connection and validation timeouts, so
     * letting it finish costs a daemon thread for a few seconds, not a hung page.
     */
    private Health await(Future<Health> future, DatabaseConfig config, long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(false);
                return timeout(config, budget.toMillis());
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            return timeout(config, budget.toMillis());
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return timeout(config, 0);
        } catch (CancellationException e) {
            return timeout(config, budget.toMillis());
        } catch (ExecutionException e) {
            return new Health(config.getId(), false, 0, messageOf(e));
        }
    }

    private static Health timeout(DatabaseConfig config, long elapsedMs) {
        return new Health(config.getId(), false, elapsedMs, TIMEOUT_MESSAGE);
    }

    private Health probe(DatabaseConfig config) {
        long start = System.currentTimeMillis();
        if (config.effectiveType() == DatabaseType.MONGODB_NATIVE) {
            return probeMongo(config, start);
        }
        HikariDataSource pool;
        try {
            pool = dataSourceManager.createProbeDataSource(config);
        } catch (RuntimeException e) {
            // Driver class missing, bad jar path, malformed URL: all reachable from the page.
            log.debug("Health check failed for '{}': {}", config.getName(), e.getMessage());
            return new Health(config.getId(), false, elapsed(start), messageOf(e));
        }
        try {
            try (Connection connection = pool.getConnection()) {
                if (!connection.isValid(VALIDATION_SECONDS)) {
                    return new Health(config.getId(), false, elapsed(start), INVALID_MESSAGE);
                }
                return new Health(config.getId(), true, elapsed(start), null);
            }
        } catch (SQLException e) {
            log.debug("Health check failed for '{}': {}", config.getName(), e.getMessage());
            return new Health(config.getId(), false, elapsed(start), JdbcUtil.sqlMessage(e));
        } catch (RuntimeException e) {
            log.debug("Health check failed for '{}': {}", config.getName(), e.getMessage());
            return new Health(config.getId(), false, elapsed(start), messageOf(e));
        } finally {
            closeProbePool(pool);
        }
    }

    /**
     * Closes a probe pool with the thread's interrupt flag temporarily clear.
     *
     * <p>The page's budget is enforced by {@code invokeAll(..., budget, ...)}, which cancels the
     * probes still running when it expires; cancellation interrupts the worker thread, and a
     * driver may leave that flag set on the way out. HikariCP reads an interrupt during
     * {@code close()} as "stop waiting now": its shutdown throws at the first
     * {@code awaitTermination} and is abandoned half-way — the pool's connections are never
     * closed — and it logs "Interrupted during closing" with a stack that reads like a failure.
     * Clearing the flag for the duration of the close and restoring it afterwards lets Hikari
     * finish; the thread belongs to a cancelled task whose result is discarded anyway.
     */
    static void closeProbePool(HikariDataSource pool) {
        if (pool == null) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            pool.close();
        } catch (RuntimeException e) {
            // A failed close must not replace the verdict the probe just produced.
            log.debug("Closing probe pool failed: {}", e.getMessage());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Health probeMongo(DatabaseConfig config, long start) {
        try {
            mongoProbe.ping(config);
            return new Health(config.getId(), true, elapsed(start), null);
        } catch (RuntimeException e) {
            log.debug("Health check failed for '{}': {}", config.getName(), e.getMessage());
            return new Health(config.getId(), false, elapsed(start), messageOf(e));
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "db-health");
            thread.setDaemon(true);
            return thread;
        };
    }
}
