package com.qqmu.jync.service.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Ensures at most one sync runs per project at any moment.
 *
 * <p>Three layers, each covering what the one before it cannot:
 *
 * <ol>
 *   <li><b>{@code @DisallowConcurrentExecution}</b> on the Quartz job stops the scheduler from
 *       starting a second fire of the same job while the first is still running. That covers
 *       the common case — a poll interval shorter than a cycle takes — but only within one
 *       live scheduler.
 *   <li><b>A JVM {@link ReentrantLock} per project</b> stops any other in-process caller, such
 *       as a manual "sync now" from the UI, from overlapping with the scheduled run. Quartz
 *       knows nothing about those callers.
 *   <li><b>A database lock row with an expiry</b> stops a second process or node from running
 *       the same project, and — because the lease expires — lets a crashed owner's lock be
 *       reclaimed rather than blocking the project forever.
 * </ol>
 *
 * <p>The database layer is what makes the guarantee survive a restart: an in-memory lock dies
 * with the process, so without it a hard kill mid-sync would leave nothing to stop a fresh
 * instance from starting an overlapping cycle.
 */
@Service
@Slf4j
public class SyncLockService {

    /** Identifies this process, so an owner can renew or reclaim only its own locks. */
    private final String ownerId;

    private final SyncLockStore store;
    private final SyncProperties properties;

    private final Map<Long, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    /**
     * Renews held leases in the background. Renewal at commit boundaries alone leaves a
     * single JDBC batch longer than the TTL unprotected, so the watchdog renews every
     * TTL/3 for the lifetime of a handle.
     */
    private static final ScheduledExecutorService WATCHDOG = createWatchdog();

    private static ScheduledExecutorService createWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "jync-lock-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        // Cancelled handles are common (every finished cycle); drop their tasks immediately.
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public SyncLockService(SyncLockStore store, SyncProperties properties,
                           @org.springframework.beans.factory.annotation.Value("${server.port:8080}") int serverPort) {
        this.store = store;
        this.properties = properties;
        this.ownerId = buildOwnerId(serverPort);
        log.info("Sync lock owner id for this instance: {}", ownerId);
    }

    private String buildOwnerId(int serverPort) {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        // host:port:instance. The random per-process suffix is the collision guard: container
        // images and cloned VMs can answer getLocalHost() with the same name (and run on the same
        // port), so host:port alone is not unique across nodes — two genuine instances would then
        // read as one owner, letting one reclaim or release the other's locks.
        //
        // This drops the old "stable across restarts" property on purpose: a fresh process gets a
        // new suffix and no longer reaps its predecessor's locks by matching owner. A crashed
        // owner's locks clear by LEASE EXPIRY (lockTtlMs) — the TTL exists precisely for owners
        // that never return — so crash recovery is unchanged; only the instant reap on a clean
        // restart is lost.
        return host + ":" + serverPort + ":" + instanceSuffix();
    }

    /** Twelve hex chars from a strong generator, so distinct processes never share an owner id. */
    private static String instanceSuffix() {
        byte[] bytes = new byte[6];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(12);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Attempts to take the lock for a project without waiting.
     *
     * <p>Non-blocking on purpose: a poll that cannot get the lock should skip this cycle and
     * try again on the next tick, not queue up behind the current run.
     *
     * @return a handle to close when done, or null when another runner holds the lock
     */
    public LockHandle tryAcquire(Long projectId) {
        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(projectId, id -> new ReentrantLock());
        if (!jvmLock.tryLock()) {
            log.debug("Project {} is already syncing in this JVM; skipping this fire", projectId);
            return null;
        }
        try {
            if (!store.acquire(projectId, ownerId, properties.getLockTtlMs())) {
                log.info("Project {} is locked by another instance; skipping this fire", projectId);
                jvmLock.unlock();
                return null;
            }
            return new LockHandle(projectId, jvmLock);
        } catch (RuntimeException e) {
            // Never leak the JVM lock when the database call fails.
            jvmLock.unlock();
            throw e;
        }
    }

    /**
     * Drops the in-process lock entry of a deleted project.
     *
     * <p>Entries are created on demand and never removed on their own, so a long-lived
     * process watching projects come and go would grow the map forever. Safe to call once
     * the project can no longer be scheduled: an in-flight cycle holds its own reference
     * to the lock object and unlocks it as usual.
     */
    public void evictProject(Long projectId) {
        jvmLocks.remove(projectId);
    }

    /**
     * Says whether a local cycle currently holds this project's JVM lock. Queued reruns use
     * it to distinguish a live holder — whose post-cycle drain will serve the queue — from
     * the gap in which the holder already released and drained and nobody is left.
     */
    public boolean isLockedInThisJvm(Long projectId) {
        ReentrantLock lock = jvmLocks.get(projectId);
        return lock != null && lock.isLocked();
    }

    /**
     * A live lease is watchdog-renewed at most this many TTLs. The cap stops a wedged
     * holder (frozen thread, deadlocked batch) from extending its lease forever — explicit
     * renewal at commit boundaries still applies while the cycle keeps making progress.
     */
    private static final int MAX_WATCHDOG_TTLS = 10;

    /** Released via try-with-resources so the lock cannot leak on an exception path. */
    public class LockHandle implements AutoCloseable {
        private final Long projectId;
        private final ReentrantLock jvmLock;
        private final ScheduledFuture<?> watchdogTask;
        private final long watchdogDeadlineMs;
        private boolean closed;

        LockHandle(Long projectId, ReentrantLock jvmLock) {
            this.projectId = projectId;
            this.jvmLock = jvmLock;
            long ttlMs = properties.getLockTtlMs();
            this.watchdogDeadlineMs = System.currentTimeMillis()
                    + (long) MAX_WATCHDOG_TTLS * ttlMs;
            long intervalMs = Math.max(1000L, ttlMs / 3);
            this.watchdogTask = WATCHDOG.scheduleAtFixedRate(this::watchdogRenew,
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        /** Renews on a timer; stops after the TTL cap or once the lease has been lost. */
        private void watchdogRenew() {
            if (closed) {
                return;
            }
            if (System.currentTimeMillis() >= watchdogDeadlineMs) {
                log.warn("Watchdog for project {} reached the {}x TTL hold limit; stopping "
                        + "automatic renewal so the lease can expire",
                        projectId, MAX_WATCHDOG_TTLS);
                watchdogTask.cancel(false);
                return;
            }
            if (!renew()) {
                watchdogTask.cancel(false);
            }
        }

        public Long projectId() {
            return projectId;
        }

        public String owner() {
            return ownerId;
        }

        /**
         * Extends the lease. Called between tables of a long-running cycle so the lock is not
         * stolen mid-run.
         *
         * @return true while the lease still belongs to this instance; false when it was lost
         *         (expired, possibly reclaimed by a peer, or the store could not be reached),
         *         meaning the cycle must stop writing — another instance may own the project
         */
        public boolean renew() {
            boolean renewed;
            try {
                renewed = store.renew(projectId, ownerId, properties.getLockTtlMs());
            } catch (RuntimeException e) {
                log.warn("Renewing the sync lock for project {} failed: {}", projectId,
                        e.getMessage());
                return false;
            }
            if (!renewed) {
                log.warn("Could not renew the sync lock for project {}; another instance may have "
                        + "taken it over", projectId);
            }
            return renewed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            watchdogTask.cancel(false);
            try {
                store.release(projectId, ownerId);
            } catch (RuntimeException e) {
                // A failed release is not fatal; the lease expires on its own.
                log.warn("Could not release the database sync lock for project {}: {}",
                        projectId, e.getMessage());
            } finally {
                if (jvmLock.isHeldByCurrentThread()) {
                    jvmLock.unlock();
                }
            }
        }
    }
}
