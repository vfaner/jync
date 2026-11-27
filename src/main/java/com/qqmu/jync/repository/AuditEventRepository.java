package com.qqmu.jync.repository;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.AuditEvent;

/**
 * Persistence for the admin audit trail.
 *
 * <p>Extends the marker {@link Repository} instead of {@code CrudRepository}/{@code JpaRepository}
 * so that no generic delete exists to call by accident. The trail is append-only by default;
 * the single exception is the administrator's explicit date-based purge below, which always
 * keeps today's rows and whose own act is recorded as a new audit event — so a purge can trim
 * history but never erase the record of itself.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    /** Latest audit row for one project (the detail starts with the project id argument). */
    java.util.Optional<AuditEvent> findFirstByActionAndDetailContainingOrderByOccurredAtDesc(
            com.qqmu.jync.model.AuditAction action, String detail);

    Page<AuditEvent> findAll(Pageable pageable);

    long count();

    /**
     * The admin date purge: everything strictly before the cutoff (the start of the day after
     * the selected day). Bulk, not a derived delete — the detail column is a LOB and loading
     * every row just to remove it would pay for contents nobody reads.
     *
     * <p>{@code @Transactional} here is load-bearing: the scheduled pruner calls this outside
     * any transaction, and a bulk delete without a transaction throws and is swallowed as a
     * warning — retention would silently never run. The sibling {@code ChangeLogRepository}
     * method carries the same annotation for the same reason.
     */
    @Modifying
    @Transactional
    @Query("delete from AuditEvent a where a.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
