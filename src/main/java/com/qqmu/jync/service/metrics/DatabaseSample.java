package com.qqmu.jync.service.metrics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private record CounterMark(Instant at, long counter, Instant configUpdatedAt) {
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
        // force=true 绕开连接页缓存（避免复用页面的超时判负）；预算按连接数缩放，让尾部
        // 健康库在多/慢连接时也能被真实探测，而不是共享页面 8s 预算耗尽被判 down。
        Duration samplingBudget = Duration.ofSeconds(Math.max(8, configs.size() * 3L));
        for (DatabaseHealthService.Health health : healthService.check(configs, true, samplingBudget)) {
            if (health != null) {
                healths.put(health.id(), health);
            }
        }
        List<DbMetricSample> rows = new ArrayList<>(configs.size());
        for (DatabaseConfig config : configs) {
            DbMetricSample row = newRow(config, now, healths.get(config.getId()));
            if (row.isOk()) {
                fillMetrics(config, row, now, includeCapacity);
            }
            try {
                sampleRepository.save(row);
                rows.add(row);
            } catch (RuntimeException e) {
                // 一个连接 save 失败（如保留修剪批量 DELETE 持锁期间 H2 锁超时）不能丢掉整轮
                // 其余连接的样本；与 MetricsSampler 的逐项目隔离一致。
                log.warn("Could not save DB metric sample for '{}': {}",
                        config.getName(), e.getMessage());
            }
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

    /**
     * Runtime and capacity figures over ONE transient pool. Each used to open its own pool and
     * login, so a capacity-due round paid two Hikari startups and two connections per
     * configured database — on a sampler that runs every minute against production servers.
     * Either read failing on its own still leaves the other one (and the health sample) intact.
     */
    private void fillMetrics(DatabaseConfig config, DbMetricSample row, Instant now,
                             boolean includeCapacity) {
        MetadataReader reader = includeCapacity
                ? readerFactory.forType(config.effectiveType()) : null;
        boolean capacityDue = reader != null && reader.reportsTableStats();
        try (HikariDataSource pool = dataSourceManager.createTransientDataSource(config);
             Connection connection = pool.getConnection()) {
            try {
                readRuntime(config, row, now, connection);
            } catch (Exception e) {
                // No permission, no view, an analytic engine: the health sample still stands.
                log.debug("Runtime metrics unavailable for '{}': {}", config.getName(),
                        e.getMessage());
            }
            if (capacityDue) {
                try {
                    readCapacity(config, row, connection, reader);
                } catch (Exception e) {
                    log.debug("Capacity unavailable for '{}': {}", config.getName(),
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Metrics connection unavailable for '{}': {}", config.getName(),
                    e.getMessage());
        }
    }

    private void readRuntime(DatabaseConfig config, DbMetricSample row, Instant now,
                             Connection connection) throws Exception {
        Optional<RuntimeMetricsReader.Reading> reading =
                router.read(config.effectiveType(), connection);
        if (reading.isEmpty()) {
            return;
        }
        RuntimeMetricsReader.Reading value = reading.get();
        row.setSessions(value.sessions());
        row.setCacheHitPercent(value.cacheHitPercent());
        row.setLockWaits(value.lockWaits());
        row.setQps(qps(config.getId(), value.counter(), now, config.getUpdatedAt()));
    }

    private Double qps(Long connectionId, Long counter, Instant now, Instant configUpdatedAt) {
        if (counter == null) {
            lastCounters.remove(connectionId);
            return null;
        }
        CounterMark previous = lastCounters.put(connectionId,
                new CounterMark(now, counter, configUpdatedAt));
        if (previous == null || !Objects.equals(previous.configUpdatedAt(), configUpdatedAt)) {
            // Nothing to subtract from yet, or the connection was edited to point at another
            // server (same id): the old cumulative counter is not comparable. null, not a
            // fabricated spike or gap; the next round heals it.
            return null;
        }
        long seconds = Duration.between(previous.at(), now).getSeconds();
        long delta = counter - previous.counter();
        // A smaller counter means the server restarted; a negative rate is not a reading.
        return seconds <= 0 || delta < 0 ? null : delta / (double) seconds;
    }

    /**
     * The "capacity" figures need a catalog query; {@code reportsTableStats()} says whether
     * the product has one. An empty answer means "cannot tell", and writing it as zero would
     * draw zero tables for a database full of them.
     */
    private void readCapacity(DatabaseConfig config, DbMetricSample row, Connection connection,
                              MetadataReader reader) throws SQLException {
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
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_LIMIT ? message : message.substring(0, MESSAGE_LIMIT);
    }
}
