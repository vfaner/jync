package com.synctool.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.synctool.model.SyncTask;
import com.synctool.model.TaskStatus;

public interface SyncTaskRepository extends JpaRepository<SyncTask, Long> {

    Optional<SyncTask> findByProjectId(Long projectId);

    List<SyncTask> findByStatus(TaskStatus status);

    void deleteByProjectId(Long projectId);

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

    /** Extends a held lock so long-running syncs are not considered crashed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncTask t set t.lockExpiresAt = :expiresAt "
            + "where t.projectId = :projectId and t.lockOwner = :owner")
    int renewLock(@Param("projectId") Long projectId,
                  @Param("owner") String owner,
                  @Param("expiresAt") Instant expiresAt);

    /** Clears locks left behind by this node's previous, unclean shutdown. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncTask t set t.lockOwner = null, t.lockExpiresAt = null where t.lockOwner = :owner")
    int releaseAllLocksOfOwner(@Param("owner") String owner);
}
