package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;

public interface SyncTaskRepository extends JpaRepository<SyncTask, Long> {

    Optional<SyncTask> findByProjectId(Long projectId);

    /** Batch fetch for list pages, so rendering N projects costs one query instead of N. */
    List<SyncTask> findByProjectIdIn(Collection<Long> projectIds);

    List<SyncTask> findByStatus(TaskStatus status);

    // Bulk, not a derived delete: a derived delete loads each entity before removing it.
    // Project deletion needs only the row gone.
    @Modifying
    @Query("delete from SyncTask t where t.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    /**
     * Atomically claims the sync lock for a project. Returns 1 when the lock was
     * acquired, 0 when another owner still holds an unexpired lock.
     *
     * <p>This is the cross-restart / cross-node half of the concurrency guard;
     * {@code @DisallowConcurrentExecution} covers only a single live scheduler.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncTask t set t.lockOwner = :owner, t.lockExpiresAt = :expiresAt "
            + "where t.projectId = :projectId "
            + "and (t.lockOwner is null or t.lockOwner = :owner or t.lockExpiresAt < :now)")
    int acquireLock(@Param("projectId") Long projectId,
                    @Param("owner") String owner,
                    @Param("expiresAt") Instant expiresAt,
                    @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncTask t set t.lockOwner = null, t.lockExpiresAt = null "
            + "where t.projectId = :projectId and t.lockOwner = :owner")
    int releaseLock(@Param("projectId") Long projectId, @Param("owner") String owner);

    /**
     * Admin override: clears the lock row regardless of owner.
     *
     * <p>The running cycle (wherever it runs) notices at its next lease renewal — renewLock
     * matches on owner and then updates zero rows — and aborts via LockLostException. So this
     * is the manual form of what TTL expiry does for a crashed owner: it unblocks the project
     * without waiting out the lease, at the cost of stopping a cycle that may still be alive.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncTask t set t.lockOwner = null, t.lockExpiresAt = null "
            + "where t.projectId = :projectId and t.lockOwner is not null")
    int clearLock(@Param("projectId") Long projectId);

    /** Extends a held lock so long-running syncs are not considered crashed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncTask t set t.lockExpiresAt = :expiresAt "
            + "where t.projectId = :projectId and t.lockOwner = :owner")
    int renewLock(@Param("projectId") Long projectId,
                  @Param("owner") String owner,
                  @Param("expiresAt") Instant expiresAt);
}
