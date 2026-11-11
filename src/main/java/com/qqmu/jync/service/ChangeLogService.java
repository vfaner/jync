package com.qqmu.jync.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.service.audit.AuditDetail;

/**
 * The change log: what happened, in order.
 *
 * <p>Read-only apart from {@link #clear}. The tasks a skipped DDL opens live in
 * {@link com.qqmu.jync.service.cdc.CdcDdlTaskService}: a task is work that is still open,
 * while a log row is a record of what already happened — mixing the two in one service
 * made both harder to read.
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
    public long count(Long projectId, boolean failedOnly) {
        if (!failedOnly) {
            return projectId != null ? repository.countByProjectId(projectId) : repository.count();
        }
        return projectId != null
                ? repository.countByProjectIdAndSuccessFalse(projectId)
                : repository.countBySuccessFalse();
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
     * with null the entire audit trail is wiped. When {@code failedOnly} is set — the
     * errors-only filter is active — only failed entries go, in the same scope.
     *
     * <p>Because this erases history, the act itself is written to the append-only admin
     * audit trail with the row count and scope — the one record a clear can never remove.
     */
    @Transactional
    public void clear(Long projectId, boolean failedOnly) {
        int removed;
        if (failedOnly) {
            removed = projectId != null
                    ? repository.deleteByProjectIdAndSuccessFalse(projectId)
                    : repository.deleteAllSuccessFalse();
        } else {
            // Not deleteByProjectId: that unfiltered delete exists for project deletion,
            // and reuse here also wiped the DDL tasks still waiting for an administrator.
            removed = projectId != null
                    ? repository.deleteByProjectIdKeepingOpenDdlTasks(projectId)
                    : repository.deleteAllBulkKeepingOpenDdlTasks();
        }
        auditService.record(AuditAction.CHANGELOG_CLEAR,
                AuditDetail.of("audit.detail.changelogClear", String.valueOf(removed),
                        failedOnly ? "@failedOnly:true" : "@failedOnly:false",
                        projectId != null
                                ? "@scope:project:" + projectId : "@scope:all"));
    }
}
