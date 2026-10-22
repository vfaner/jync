package com.qqmu.jync.service.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   <li><b>A JVM {@link Semaphore} (one permit) per project</b> stops any other in-process
 *       caller, such as a manual "sync now" from the UI, from overlapping with the scheduled
 *       run. Quartz knows nothing about those callers. A semaphore rather than a
 *       {@code ReentrantLock} because the CDC daemon lease is acquired on the manager's
 *       thread but closed on the consumer thread, and only a semaphore's permit can be
 *       released from a thread other than the acquirer. No caller ever takes the same
 *       project's lock twice on one thread (audited), so the lost reentrancy is unused;
 *       {@code tryAcquire()} stays the same non-blocking gate {@code tryLock()} was.
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

    private final Map<Long, Semaphore> jvmLocks = new ConcurrentHashMap<>();

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
        return tryAcquire(projectId, properties.getLockTtlMs(), MAX_WATCHDOG_TTLS);
    }

    /**
     * Long-lived lease for daemon consumers (CDC). Renewals are unbounded: the lease
     * dies only when the process dies or the store becomes unreachable, which is
     * exactly the takeover signal task book B6 asks for.
     */
    public LockHandle tryAcquireForDaemon(Long projectId, long ttlMs) {
        return tryAcquire(projectId, ttlMs, Integer.MAX_VALUE);
    }

    private LockHandle tryAcquire(Long projectId, long ttlMs, int maxRenewals) {
        Semaphore jvmLock = jvmLocks.computeIfAbsent(projectId, id -> new Semaphore(1));
        if (!jvmLock.tryAcquire()) {
            log.debug("Project {} is already syncing in this JVM; skipping this fire", projectId);
            return null;
        }
        try {
            if (!store.acquire(projectId, ownerId, ttlMs)) {
                // Steady state for the passive side of a multi-instance deployment: the other
                // instance holds the lease and runs the cycle. Logging this per fire at INFO
                // turns an idle project into a line every poll interval.
                log.debug("Project {} is locked by another instance; skipping this fire", projectId);
                jvmLock.release();
                return null;
            }
            return new LockHandle(projectId, jvmLock, ttlMs, maxRenewals);
        } catch (RuntimeException e) {
            // Never leak the JVM lock when the database call fails.
            jvmLock.release();
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
        Semaphore lock = jvmLocks.get(projectId);
        return lock != null && lock.availablePermits() == 0;
    }

    /**
     * Read-only database-lease holder check for cross-node gates (CDC rebaseline, final
     * review FIX 1): true when the project's lease is owned by ANOTHER owner and has not
     * expired.
     *
     * <p>The freshness predicate is the exact negation of the takeover clause in
     * {@code SyncTaskRepository.acquireLock} ({@code lockOwner is null or lockOwner = :owner
     * or lockExpiresAt < :now}) — the same TTL-freshness notion supervise's takeover already
     * relies on, so there is exactly one definition of "live owner". Deliberately NOT
     * acquire-then-release: acquiring would steal (or fail against) a genuine holder's
     * lease mid-stream; this only reads the row.
     */
    public boolean isLeaseHeldByAnotherLiveOwner(Long projectId) {
        return store.findTask(projectId)
                .map(task -> task.getLockOwner() != null
                        && !ownerId.equals(task.getLockOwner())
                        && (task.getLockExpiresAt() == null
                                || !task.getLockExpiresAt().isBefore(java.time.Instant.now())))
                .orElse(false);
    }

    /**
     * Admin override: drops the database lease whoever holds it, so a wedged or crashed
     * owner's project can be unlocked without waiting out the TTL.
     *
     * <p>Only the database row is cleared here — the JVM {@link Semaphore} of a cycle
     * running <em>on this node</em> stays held until that cycle notices its lost lease at the
     * next renewal and unwinds. That is deliberate: the in-flight thread must keep owning its
     * lock until it stops writing, or a second local cycle could start on top of it. The
     * holder aborts via {@code LockLostException}; the audit trail records who forced it.
     *
     * @return true when a lease was actually cleared; false when the lock was already free
     */
    public boolean forceUnlock(Long projectId) {
        int cleared = store.forceRelease(projectId);
        if (cleared > 0) {
            log.warn("Admin force-unlocked the sync lease for project {}", projectId);
            return true;
        }
        return false;
    }

    /**
     * Polling cycles are bounded: the watchdog gives up after this many renewals. The cap
     * stops a wedged holder (frozen thread, deadlocked batch) from extending its lease
     * forever — explicit renewal at commit boundaries still applies while the cycle keeps
     * making progress. Daemon leases opt out via {@link #tryAcquireForDaemon}.
     */
    private static final int MAX_WATCHDOG_TTLS = 10;

    /** Released via try-with-resources so the lock cannot leak on an exception path. */
    public class LockHandle implements AutoCloseable {
        private final Long projectId;
        private final Semaphore jvmLock;
        private final long ttlMs;
        private final int maxRenewals;
        private final ScheduledFuture<?> watchdogTask;
        private final long watchdogDeadlineMs;
        /**
         * Exactly-once guard for {@link #close}. Atomic rather than a plain flag because
         * close is now legitimately called from a thread other than the acquirer's (the CDC
         * consumer closes its lease on the consumer thread): two racing closes must not both
         * release — a semaphore permit released twice inflates the count and destroys the
         * exclusion this class exists for.
         */
        private final AtomicBoolean closed = new AtomicBoolean();

        LockHandle(Long projectId, Semaphore jvmLock, long ttlMs, int maxRenewals) {
            this.projectId = projectId;
            this.jvmLock = jvmLock;
            this.ttlMs = ttlMs;
            this.maxRenewals = maxRenewals;
            this.watchdogDeadlineMs = System.currentTimeMillis()
                    + (long) maxRenewals * ttlMs;
            long intervalMs = Math.max(1000L, ttlMs / 3);
            this.watchdogTask = WATCHDOG.scheduleAtFixedRate(this::watchdogRenew,
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        /** Renews on a timer; stops after the TTL cap or once the lease has been lost. */
        private void watchdogRenew() {
            if (closed.get()) {
                return;
            }
            if (System.currentTimeMillis() >= watchdogDeadlineMs) {
                log.warn("Watchdog for project {} reached the {}x TTL hold limit; stopping "
                        + "automatic renewal so the lease can expire",
                        projectId, maxRenewals);
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
                renewed = store.renew(projectId, ownerId, ttlMs);
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

        /**
         * The watchdog renewal cap of this lease: {@code MAX_WATCHDOG_TTLS} for polling
         * cycles, {@code Integer.MAX_VALUE} for daemon leases. Package-private for tests.
         */
        int maxWatchdogRenewals() {
            return maxRenewals;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;                                          // 幂等:恰好释放一次
            }
            watchdogTask.cancel(false);
            try {
                store.release(projectId, ownerId);
            } catch (RuntimeException e) {
                // A failed release is not fatal; the lease expires on its own.
                log.warn("Could not release the database sync lock for project {}: {}",
                        projectId, e.getMessage());
            } finally {
                // Thread-agnostic release: the CDC daemon lease is acquired on the manager's
                // thread (ensureStarted) and closed on the consumer thread (CdcConsumer's
                // finally). The old isHeldByCurrentThread()-guarded ReentrantLock unlock
                // silently skipped there and leaked the per-project JVM lock permanently —
                // every later acquire on this node failed, wedging the project until restart.
                jvmLock.release();
            }
        }
    }
}
