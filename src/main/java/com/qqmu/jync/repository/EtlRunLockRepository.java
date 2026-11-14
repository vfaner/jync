package com.qqmu.jync.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.EtlRunLock;

/**
 * The conditional statements behind the ETL execution lease. The WHERE clause is the guard: two
 * nodes racing on {@link #claim} cannot both match the row, so exactly one wins without any
 * application-level locking.
 */
public interface EtlRunLockRepository extends JpaRepository<EtlRunLock, String> {

    /**
     * Takes an existing row when it is free, already ours, or expired — the same freshness rule
     * as {@code SyncTaskRepository.acquireLock}, so "live owner" means one thing across the app.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EtlRunLock l set l.ownerId = :owner, l.expiresAt = :expiresAt "
            + "where l.lockKey = :key "
            + "and (l.ownerId = :owner or l.expiresAt < :now)")
    int claim(@Param("key") String key,
              @Param("owner") String owner,
              @Param("expiresAt") Instant expiresAt,
              @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EtlRunLock l set l.expiresAt = :expiresAt "
            + "where l.lockKey = :key and l.ownerId = :owner")
    int renew(@Param("key") String key,
              @Param("owner") String owner,
              @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EtlRunLock l where l.lockKey = :key and l.ownerId = :owner")
    int release(@Param("key") String key, @Param("owner") String owner);

    /** Drops the row whoever owns it; only for a task or batch that no longer exists. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EtlRunLock l where l.lockKey = :key")
    int discard(@Param("key") String key);
}
