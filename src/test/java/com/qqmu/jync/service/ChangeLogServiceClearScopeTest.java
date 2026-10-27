package com.qqmu.jync.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * "Clear" under the errors-only filter must remove only failed entries: the clear form
 * carries a hidden {@code failedOnly} field, and ignoring it wiped the whole log —
 * including the successes the user was still looking at around — from a button that
 * read "clear errors".
 */
@ExtendWith(MockitoExtension.class)
class ChangeLogServiceClearScopeTest {

    @Mock private ChangeLogRepository repository;
    @Mock private AdminAuditService auditService;

    private ChangeLogService service() {
        return new ChangeLogService(repository, auditService);
    }

    @Test
    void failedOnlyClearOfOneProjectDeletesOnlyThatProjectsFailures() {
        when(repository.deleteByProjectIdAndSuccessFalse(7L)).thenReturn(2);

        service().clear(7L, true);

        verify(repository).deleteByProjectIdAndSuccessFalse(7L);
        verify(repository, never()).deleteByProjectId(any());
        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR), contains("2"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR), contains("failed"));
    }

    @Test
    void failedOnlyClearWithoutProjectFilterDeletesOnlyFailures() {
        when(repository.deleteAllSuccessFalse()).thenReturn(5);

        service().clear(null, true);

        verify(repository).deleteAllSuccessFalse();
        verify(repository, never()).deleteAllBulk();
        verify(auditService).record(eq(AuditAction.CHANGELOG_CLEAR), contains("5"));
    }

    @Test
    void aPlainClearStillDeletesEverythingInScope() {
        when(repository.deleteByProjectId(7L)).thenReturn(3);

        service().clear(7L, false);

        verify(repository).deleteByProjectId(7L);
        verify(repository, never()).deleteByProjectIdAndSuccessFalse(any());
    }
}
