package com.qqmu.jync.service.cdc;

import com.qqmu.jync.service.sync.SyncContext;

/**
 * Records the capture strategy's current position as the baseline anchor: the point the stream
 * resumes from once the full load finishes.
 *
 * <p>A functional dependency rather than a service reference, so the Redis baseline can record the
 * same anchor without depending on {@link CdcBaselineService} — which depends on it.
 */
@FunctionalInterface
public interface CdcBaselineAnchor {

    /**
     * @return the LogMiner SCN the baseline anchored at, or {@code null} for every other
     *         strategy; the JDBC baseline re-checks it against the archive floor after the load
     */
    Long record(SyncContext ctx);
}
