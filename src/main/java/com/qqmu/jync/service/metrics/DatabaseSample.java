package com.qqmu.jync.service.metrics;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metrics.runtime.RuntimeMetricsReader;
import com.qqmu.jync.service.metrics.runtime.RuntimeMetricsRouter;

import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Takes one reading of every configured connection.
 *
 * <p>Not just the ones an enabled project points at: every row of the connection list offers
 * a performance page, so a connection with no project (or only a disabled one) would leave
 * that page permanently on "no samples yet". The cost is one short-lived probe per connection
 * per interval — the same order as the health check the alert evaluator already runs against
 * all connections, and it is tunable through {@code metrics.db-interval-ms}.
 *
 * <p>The connection list is re-read on every round, never cached: a connection deleted
 * between two rounds must not be dialled again. Anything that goes wrong while reading a
 * figure leaves that figure null — the health verdict it was collected alongside stands,
 * because "the monitor lacks permission" is not "the database is down".
 */
@Component
@Slf4j
public class DatabaseSample {

    /** A driver's message can be a paragraph of stack-ish text; the page shows a line. */
    private static final int MESSAGE_LIMIT = 500;

    private final DatabaseConfigRepository configRepository;
    private final DatabaseHealthService healthService;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final RuntimeMetricsRouter router;
    private final DbMetricSampleRepository sampleRepository;

    /** Previous raw execution counter per connection, so the next reading can be a rate. */
    private final Map<Long, CounterMark> lastCounters = new ConcurrentHashMap<>();

    public DatabaseSample(DatabaseConfigRepository configRepository,
                          DatabaseHealthService healthService,
                          DataSourceManager dataSourceManager,
                          MetadataReaderFactory readerFactory,
                          RuntimeMetricsRouter router,
                          DbMetricSampleRepository sampleRepository) {
        this.configRepository = configRepository;
        this.healthService = healthService;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.router = router;
        this.sampleRepository = sampleRepository;
    }

    private record CounterMark(Instant at, long counter) {
    }

    public List<DbMetricSample> sample(Instant now, boolean includeCapacity) {
        List<DatabaseConfig> configs = configRepository.findAll();
        // Forget the marks of deleted connections. Keeping them would price a
        // delete-then-recreate as one long interval, and the map would grow for the life of the
        // process by one entry per connection ever sampled.
        Set<Long> live = new HashSet<>();
        for (DatabaseConfig config : configs) {
            live.add(config.getId());
        }
        lastCounters.keySet().retainAll(live);
        if (configs.isEmpty()) {
            return List.of();
        }
        Map<Long, DatabaseHealthService.Health> healths = new LinkedHashMap<>();
        for (DatabaseHealthService.Health health : healthService.check(configs, false)) {
            if (health != null) {
                healths.put(health.id(), health);
            }
        }
        List<DbMetricSample> rows = new ArrayList<>(configs.size());
        for (DatabaseConfig config : configs) {
            DbMetricSample row = newRow(config, now, healths.get(config.getId()));
            if (row.isOk()) {
                fillRuntime(config, row, now);
                if (includeCapacity) {
                    fillCapacity(config, row);
                }
            }
            sampleRepository.save(row);
            rows.add(row);
        }
        return rows;
    }

    private DbMetricSample newRow(DatabaseConfig config, Instant now,
                                  DatabaseHealthService.Health health) {
        DbMetricSample row = new DbMetricSample();
        row.setConnectionId(config.getId());
        row.setSampledAt(now);
        row.setOk(health != null && health.ok());
        if (health != null) {
            row.setLatencyMs(health.elapsedMs());
            row.setMessage(health.ok() ? null : truncate(health.message()));
        }
        return row;
    }

    private void fillRuntime(DatabaseConfig config, DbMetricSample row, Instant now) {
        try (HikariDataSource pool = dataSourceManager.createTransientDataSource(config);
             Connection connection = pool.getConnection()) {
            Optional<RuntimeMetricsReader.Reading> reading =
                    router.read(config.effectiveType(), connection);
            if (reading.isEmpty()) {
                return;
            }
            RuntimeMetricsReader.Reading value = reading.get();
            row.setSessions(value.sessions());
            row.setCacheHitPercent(value.cacheHitPercent());
            row.setLockWaits(value.lockWaits());
            row.setQps(qps(config.getId(), value.counter(), now));
        } catch (Exception e) {
            // No permission, no view, an analytic engine: the health sample still stands.
            log.debug("Runtime metrics unavailable for '{}': {}", config.getName(), e.getMessage());
        }
    }

    private Double qps(Long connectionId, Long counter, Instant now) {
        if (counter == null) {
            lastCounters.remove(connectionId);
            return null;
        }
        CounterMark previous = lastCounters.put(connectionId, new CounterMark(now, counter));
        if (previous == null) {
            // Nothing to subtract from yet: null, not zero, so the chart does not claim a
            // minute of silence that never happened.
            return null;
        }
        long seconds = Duration.between(previous.at(), now).getSeconds();
        long delta = counter - previous.counter();
        // A smaller counter means the server restarted; a negative rate is not a reading.
        return seconds <= 0 || delta < 0 ? null : delta / (double) seconds;
    }

    private void fillCapacity(DatabaseConfig config, DbMetricSample row) {
        MetadataReader reader = readerFactory.forType(config.effectiveType());
        if (!reader.reportsTableStats()) {
            // No catalog query for this product: an empty answer means "cannot tell", and
            // writing it as zero would draw zero tables for a database full of them.
            return;
        }
        try (HikariDataSource pool = dataSourceManager.createTransientDataSource(config);
             Connection connection = pool.getConnection()) {
            String schema = config.getSchemaName();
            if (schema == null || schema.isBlank()) {
                schema = reader.resolveDefaultSchema(connection);
            }
            Map<String, MetadataReader.TableStat> stats = reader.estimateTableStats(connection, schema);
            long rows = 0;
            long bytes = 0;
            for (MetadataReader.TableStat stat : stats.values()) {
                rows += stat.rows() == null ? 0 : stat.rows();
                bytes += stat.bytes() == null ? 0 : stat.bytes();
            }
            row.setTableCount(stats.size());
            row.setRowCount(rows);
            row.setBytes(bytes);
        } catch (Exception e) {
            log.debug("Capacity unavailable for '{}': {}", config.getName(), e.getMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_LIMIT ? message : message.substring(0, MESSAGE_LIMIT);
    }
}
