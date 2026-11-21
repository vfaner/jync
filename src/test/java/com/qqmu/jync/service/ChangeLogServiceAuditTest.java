package com.qqmu.jync.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * Purging the change log erases history, so the purge itself must land in the admin audit
 * trail — with the row count, the cutoff day and the scope, which is all an investigator has
 * left once the rows are gone.
 */
@ExtendWith(MockitoExtension.class)
class ChangeLogServiceAuditTest {

    @Mock private ChangeLogRepository repository;
    @Mock private AdminAuditService auditService;

    private final LocalDate yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1);

    @Test
    void anUnscopedPurgeAuditsTheTotalRowCountAndCutoff() {
        when(repository.purgeBefore(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), eq(false))).thenReturn(42);
        ChangeLogService service = new ChangeLogService(repository, auditService);

        service.purgeBefore(yesterday, null, false);

        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE), contains("|42|"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE),
                contains("|" + yesterday + "|"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE), contains("@scope:all"));
    }

    @Test
    void aProjectScopedPurgeAuditsTheProjectAndRowCount() {
        when(repository.purgeBefore(org.mockito.ArgumentMatchers.any(), eq(7L), eq(false)))
                .thenReturn(3);
        ChangeLogService service = new ChangeLogService(repository, auditService);

        service.purgeBefore(yesterday, 7L, false);

        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE),
                contains("@scope:project:7"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE), contains("|3|"));
    }
}
