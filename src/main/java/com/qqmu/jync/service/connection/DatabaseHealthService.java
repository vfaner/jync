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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.DatabaseConfig;
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
    private final Duration budget;
    private final Duration cacheTtl;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    /** Two constructors: this one is what Spring wires; the other exists for the tests. */
    @Autowired
    public DatabaseHealthService(DataSourceManager dataSourceManager) {
        this(dataSourceManager, DEFAULT_BUDGET, DEFAULT_CACHE_TTL);
    }

    DatabaseHealthService(DataSourceManager dataSourceManager, Duration budget, Duration cacheTtl) {
        this.dataSourceManager = dataSourceManager;
        this.budget = budget;
        this.cacheTtl = cacheTtl;
    }

    /** One row's verdict. {@code message} is null when healthy. */
    public record Health(Long id, boolean ok, long elapsedMs, String message) {
    }

    private record Cached(Health health, Instant expiresAt, Instant configUpdatedAt) {
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
            List<Callable<Health>> tasks = new ArrayList<>(pending.size());
            for (DatabaseConfig config : pending) {
                tasks.add(() -> probe(config));
            }
            List<Future<Health>> futures =
                    executor.invokeAll(tasks, budget.toMillis(), TimeUnit.MILLISECONDS);
            Instant expiresAt = Instant.now().plus(cacheTtl);
            for (int i = 0; i < futures.size(); i++) {
                DatabaseConfig config = pending.get(i);
                Future<Health> future = futures.get(i);
                Health health;
                if (future.isCancelled()) {
                    health = new Health(config.getId(), false, budget.toMillis(), TIMEOUT_MESSAGE);
                } else {
                    health = await(future, config);
                }
                results.put(config.getId(), health);
                cache.put(config.getId(),
                        new Cached(health, expiresAt, config.getUpdatedAt()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (DatabaseConfig config : pending) {
                results.putIfAbsent(config.getId(),
                        new Health(config.getId(), false, 0, TIMEOUT_MESSAGE));
            }
        } finally {
            // Interrupts a dial still in flight; its task closes its own pool on the way out.
            executor.shutdownNow();
        }
    }

    private Health await(Future<Health> future, DatabaseConfig config) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Health(config.getId(), false, 0, TIMEOUT_MESSAGE);
        } catch (Exception e) {
            return new Health(config.getId(), false, 0, messageOf(e));
        }
    }

    private Health probe(DatabaseConfig config) {
        long start = System.currentTimeMillis();
        try (HikariDataSource pool = dataSourceManager.createProbeDataSource(config);
             Connection connection = pool.getConnection()) {
            if (!connection.isValid(VALIDATION_SECONDS)) {
                return new Health(config.getId(), false, elapsed(start), INVALID_MESSAGE);
            }
            return new Health(config.getId(), true, elapsed(start), null);
        } catch (SQLException e) {
            log.debug("Health check failed for '{}': {}", config.getName(), e.getMessage());
            return new Health(config.getId(), false, elapsed(start), sqlMessage(e));
        } catch (RuntimeException e) {
            // Driver class missing, bad jar path, malformed URL: all reachable from the page.
            log.debug("Health check failed for '{}': {}", config.getName(), e.getMessage());
            return new Health(config.getId(), false, elapsed(start), messageOf(e));
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** Same shape as the test button's message, so one driver error reads the same in both. */
    private static String sqlMessage(SQLException e) {
        StringBuilder sb = new StringBuilder();
        if (e.getSQLState() != null) {
            sb.append("[SQLState ").append(e.getSQLState()).append("] ");
        }
        sb.append(e.getMessage());
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null
                && !cause.getMessage().equals(e.getMessage())) {
            sb.append(" (cause: ").append(cause.getMessage()).append(')');
        }
        return sb.toString();
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
