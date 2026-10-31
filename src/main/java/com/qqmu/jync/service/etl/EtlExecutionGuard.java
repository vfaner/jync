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

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** A task's slot. Batches use their own {@code "batch:<id>"} key through the string API. */
    public boolean tryAcquire(Long taskId) {
        return tryAcquire(taskKey(taskId));
    }

    public boolean tryAcquire(String key) {
        return running.add(key);
    }

    public void release(Long taskId) {
        release(taskKey(taskId));
    }

    public void release(String key) {
        running.remove(key);
    }

    public boolean isRunning(Long taskId) {
        return isRunning(taskKey(taskId));
    }

    public boolean isRunning(String key) {
        return running.contains(key);
    }

    private static String taskKey(Long taskId) {
        return "task:" + taskId;
    }
}
