package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.SQLException;

import com.qqmu.jync.model.DatabaseType;

/**
 * Reads the in-database counters a product exposes through its dictionary views.
 *
 * <p>Only the figures the product really reports go into {@link Reading}; everything else
 * stays null so the page can show "—" instead of an invented number. Missing permissions
 * surface as a {@link SQLException}, which the sampler records as a failed sample.
 */
public interface RuntimeMetricsReader {

    boolean supports(DatabaseType type);

    /**
     * @param counter a monotonically increasing execution counter; the sampler differences
     *                two readings to get QPS, so the reader must not divide anything itself
     */
    Reading read(Connection connection) throws SQLException;

    record Reading(Integer sessions, Long counter, Double cacheHitPercent, Integer lockWaits) {
    }
}
