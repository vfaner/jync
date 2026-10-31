package com.qqmu.jync.service.etl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Prevents one node from running the same ETL task twice at once, whether the second trigger
 * is the schedule firing again or an operator pressing "run now".
 *
 * <p>Deliberately in-memory: ETL is an operator-initiated batch job, not the always-on sync
 * engine, and an abandoned run leaves visible partial data that the operator can repair by
 * running again. A shared database lease can be added later if ETL is ever run on several
 * nodes.
 */
@Component
public class EtlExecutionGuard {

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public boolean tryAcquire(Long taskId) {
        return running.add(taskId);
    }

    public void release(Long taskId) {
        running.remove(taskId);
    }

    public boolean isRunning(Long taskId) {
        return running.contains(taskId);
    }
}
