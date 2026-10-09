package com.qqmu.jync.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * Clearing the change log erases history, so the clear itself must land in the append-only
 * audit trail — with the row count and the scope, which is all an investigator has left.
 */
@ExtendWith(MockitoExtension.class)
class ChangeLogServiceAuditTest {

    @Mock private ChangeLogRepository repository;
    @Mock private AdminAuditService auditService;

    @Test
    void aFullClearAuditsTheTotalRowCount() {
        when(repository.deleteAllBulk()).thenReturn(42);
        ChangeLogService service = new ChangeLogService(repository, auditService);

        service.clear(null);

        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR),
                contains("42"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR),
                contains("entire history"));
    }

    @Test
    void aProjectScopedClearAuditsTheProjectAndRowCount() {
        when(repository.deleteByProjectId(7L)).thenReturn(3);
        ChangeLogService service = new ChangeLogService(repository, auditService);

        service.clear(7L);

        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR),
                contains("project 7"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR),
                contains("3"));
    }
}
