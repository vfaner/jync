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
     * Removes change-log entries on or before {@code day} (the whole selected day included),
     * scoped like the page the operator is looking at: a project id narrows to that project,
     * {@code failedOnly} narrows to failed entries. Open skipped-DDL tasks are work, not
     * history, and survive every purge; today's entries always survive, so a date purge can
     * never wipe the log in one go — there is deliberately no clear-all.
     *
     * <p>Because this erases history, the act itself is written to the admin audit trail with
     * the row count, cutoff and scope — the one record a purge can never remove.
     *
     * @return how many entries were removed
     * @throws IllegalArgumentException {@code log.purge.date.invalid} for a null/too-new day
     */
    @Transactional
    public int purgeBefore(java.time.LocalDate day, Long projectId, boolean failedOnly) {
        java.time.Instant cutoff = com.qqmu.jync.util.LogPurgeDates.cutoffFor(day);
        int removed = repository.purgeBefore(cutoff, projectId, failedOnly);
        auditService.record(AuditAction.CHANGELOG_PURGE,
                AuditDetail.of("audit.detail.changelogPurge", String.valueOf(removed),
                        day.toString(),
                        failedOnly ? "@failedOnly:true" : "@failedOnly:false",
                        projectId != null
                                ? "@scope:project:" + projectId : "@scope:all"));
        return removed;
    }
}
