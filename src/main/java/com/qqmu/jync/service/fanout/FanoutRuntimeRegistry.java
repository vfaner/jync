package com.qqmu.jync.service.fanout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * The running {@link FanoutRuntime} of this node, keyed by project id.
 *
 * <p>The JDBC engines (PG logical, LogMiner, SQL Server, YStream) share their stream code with
 * the single-target path; only two seams differ under fan-out — the baseline and the target
 * transaction. This registry is how those seams find the runtime without every engine consumer
 * growing a fan-out branch: the wrapper consumer registers the runtime before the engine runs
 * and removes it afterwards, so a late lookup can never resurrect a closed runtime.
 */
@Service
public class FanoutRuntimeRegistry {

    private final Map<Long, FanoutRuntime> runtimes = new ConcurrentHashMap<>();

    public void register(Long projectId, FanoutRuntime runtime) {
        FanoutRuntime previous = runtimes.put(projectId, runtime);
        if (previous != null && previous != runtime) {
            previous.close();
        }
    }

    public FanoutRuntime get(Long projectId) {
        return projectId == null ? null : runtimes.get(projectId);
    }

    /** Removes and returns the runtime; the caller owns closing it. */
    public FanoutRuntime remove(Long projectId) {
        return projectId == null ? null : runtimes.remove(projectId);
    }

    /**
     * Removes the runtime only if it is still the registered one. A consumer whose stop
     * overran the join budget can finish after its replacement registered; an unconditional
     * remove would silently unregister the live runtime and hide it from DDL invalidation.
     */
    public void remove(Long projectId, FanoutRuntime runtime) {
        if (projectId != null && runtime != null) {
            runtimes.remove(projectId, runtime);
        }
    }
}
