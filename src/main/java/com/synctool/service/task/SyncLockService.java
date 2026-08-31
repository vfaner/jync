package com.synctool.service.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.synctool.config.SyncProperties;
import com.synctool.model.SyncTask;

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

    public SyncLockService(SyncLockStore store, SyncProperties properties) {
        this.store = store;
        this.properties = properties;
        this.ownerId = buildOwnerId();
        log.info("Sync lock owner id for this instance: {}", ownerId);
    }

    private String buildOwnerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        // The PID distinguishes two instances started on the same machine.
        return host + ":" + ProcessHandle.current().pid();
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
     * Clears locks this instance left behind after an unclean shutdown.
     *
     * <p>Runs at startup and touches only this owner's rows, so a genuinely live peer keeps its
     * lock.
     */
    public int releaseStaleLocksOfThisOwner() {
        int released = store.releaseAllOf(ownerId);
        if (released > 0) {
            log.info("Released {} sync lock(s) left over from a previous run of this instance",
                    released);
        }
        return released;
    }

    /** Reports who currently holds a project's lock, for display in the UI. */
    public String currentHolder(Long projectId) {
        return store.findTask(projectId).map(SyncTask::getLockOwner).orElse(null);
    }

    /** True when this instance holds the project's lock. */
    public boolean isHeldByThisInstance(Long projectId) {
        return ownerId.equals(currentHolder(projectId));
    }

    /** Released via try-with-resources so the lock cannot leak on an exception path. */
    public class LockHandle implements AutoCloseable {
        private final Long projectId;
        private final ReentrantLock jvmLock;
        private boolean closed;

        LockHandle(Long projectId, ReentrantLock jvmLock) {
            this.projectId = projectId;
            this.jvmLock = jvmLock;
        }

        public Long projectId() {
            return projectId;
        }

        public String owner() {
            return ownerId;
        }

        /** Extends the lease. Call from a long-running cycle so the lock is not stolen mid-run. */
        public void renew() {
            if (!store.renew(projectId, ownerId, properties.getLockTtlMs())) {
                log.warn("Could not renew the sync lock for project {}; another instance may have "
                        + "taken it over", projectId);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
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
