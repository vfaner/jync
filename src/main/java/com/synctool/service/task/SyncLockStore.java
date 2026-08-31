package com.synctool.service.task;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.model.SyncTask;
import com.synctool.repository.SyncTaskRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional gateway for the lock row.
 *
 * <p>A separate bean because Spring's {@code @Transactional} works through a proxy: had these
 * methods lived on {@link SyncLockService} and been called from its own {@code tryAcquire},
 * the self-invocation would bypass the proxy and the {@code @Modifying} queries would run
 * without a transaction. Crossing a bean boundary is what actually gives each lock operation
 * its own committed transaction.
 */
@Service
@Slf4j
public class SyncLockStore {

    private final SyncTaskRepository taskRepository;

    public SyncLockStore(SyncTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * Conditionally claims the lock. The UPDATE's WHERE clause is the guard, so two instances
     * racing here cannot both succeed — exactly one row update matches.
     *
     * @return true when this owner now holds the lock
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(Long projectId, String owner, long ttlMs) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(ttlMs);
        int updated = taskRepository.acquireLock(projectId, owner, expiresAt, now);
        if (updated > 0) {
            return true;
        }
        if (taskRepository.findByProjectId(projectId).isEmpty()) {
            log.warn("No sync task row exists for project {}; cannot acquire its lock", projectId);
        }
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long projectId, String owner) {
        taskRepository.releaseLock(projectId, owner);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(Long projectId, String owner, long ttlMs) {
        Instant expiresAt = Instant.now().plusMillis(ttlMs);
        return taskRepository.renewLock(projectId, owner, expiresAt) > 0;
    }

    /** Clears every lock held by one owner; used to clean up after an unclean shutdown. */
    @Transactional
    public int releaseAllOf(String owner) {
        return taskRepository.releaseAllLocksOfOwner(owner);
    }

    @Transactional(readOnly = true)
    public Optional<SyncTask> findTask(Long projectId) {
        return taskRepository.findByProjectId(projectId);
    }
}
