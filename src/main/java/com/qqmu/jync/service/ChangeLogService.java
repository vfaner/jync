package com.qqmu.jync.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * Read and maintenance operations over the change log.
 *
 * <p>The write path lives here rather than on the controller so the transaction boundary
 * sits with the rest of the application's writes, in the service layer.
 */
@Service
public class ChangeLogService {

    private final ChangeLogRepository repository;
    private final AdminAuditService auditService;

    public ChangeLogService(ChangeLogRepository repository, AdminAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public long count(Long projectId) {
        return count(projectId, false);
    }

    @Transactional(readOnly = true)
    public long count(Long projectId, boolean failedOnly) {
        if (!failedOnly) {
            return projectId != null ? repository.countByProjectId(projectId) : repository.count();
        }
        return projectId != null
                ? repository.countByProjectIdAndSuccessFalse(projectId)
                : repository.countBySuccessFalse();
    }

    @Transactional(readOnly = true)
    public Page<ChangeLog> page(Long projectId, Pageable pageable) {
        return page(projectId, false, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ChangeLog> page(Long projectId, boolean failedOnly, Pageable pageable) {
        if (!failedOnly) {
            return projectId != null
                    ? repository.findByProjectIdOrderByOccurredAtDesc(projectId, pageable)
                    : repository.findAllByOrderByOccurredAtDesc(pageable);
        }
        return projectId != null
                ? repository.findByProjectIdAndSuccessFalseOrderByOccurredAtDesc(projectId, pageable)
                : repository.findBySuccessFalseOrderByOccurredAtDesc(pageable);
    }

    /**
     * Clears change-log entries. With a project id only that project's logs are removed;
     * with null the entire audit trail is wiped.
     *
     * <p>Because this erases history, the act itself is written to the append-only admin
     * audit trail with the row count and scope — the one record a clear can never remove.
     */
    @Transactional
    public void clear(Long projectId) {
        int removed = projectId != null
                ? repository.deleteByProjectId(projectId)
                : repository.deleteAllBulk();
        auditService.record(AuditAction.CHANGELOG_CLEAR, "Cleared " + removed
                + (projectId != null
                        ? " change-log entries of project " + projectId
                        : " change-log entries (entire history)"));
    }
}
