package com.qqmu.jync.service.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.MetricsProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Drives {@link DatabaseSample} and decides when the expensive capacity sweep rides along.
 *
 * <p>Health and runtime metrics are one cheap read per connection; the capacity sweep asks
 * the optimizer statistics of every table, so it runs on its own slower interval. A failure
 * is logged and swallowed — an exception escaping a fixed-delay task cancels every later run.
 */
@Component
@Slf4j
public class DatabaseMetricsSampler {

    private final DatabaseSample databaseSample;
    private final MetricsProperties properties;
    private final Clock clock;
    private Instant lastCapacity;

    @Autowired
    public DatabaseMetricsSampler(DatabaseSample databaseSample, MetricsProperties properties) {
        this(databaseSample, properties, Clock.systemUTC());
    }

    /** Test seam: a clock the test moves instead of waiting an hour. */
    DatabaseMetricsSampler(DatabaseSample databaseSample, MetricsProperties properties,
                           Clock clock) {
        this.databaseSample = databaseSample;
        this.properties = properties;
        this.clock = clock;
    }

    // First round after 30 s, not a full interval: the performance pages should have a point
    // by the time anyone opens them after a restart.
    @Scheduled(fixedDelayString = "${metrics.db-interval-ms:300000}",
            initialDelayString = "${metrics.db-initial-delay-ms:30000}")
    public void sampleDatabases() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Instant now = clock.instant();
            boolean capacityDue = lastCapacity == null
                    || Duration.between(lastCapacity, now).toMillis()
                            >= properties.getCapacityIntervalMs();
            databaseSample.sample(now, capacityDue);
            if (capacityDue) {
                lastCapacity = now;
            }
        } catch (Exception e) {
            log.warn("Database metric sample failed: {}", e.getMessage());
        }
    }
}
