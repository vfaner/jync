package com.qqmu.jync.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import com.qqmu.jync.model.AuditEvent;

/**
 * Persistence for the admin audit trail.
 *
 * <p>Extends the marker {@link Repository} instead of {@code CrudRepository}/{@code JpaRepository}
 * so that the append-only guarantee is structural: no delete or deleteAll method exists to call,
 * and nothing in the codebase can remove audit rows by accident or by admin request. Paging is
 * the only read shape the UI needs.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    Page<AuditEvent> findAll(Pageable pageable);

    long count();
}
