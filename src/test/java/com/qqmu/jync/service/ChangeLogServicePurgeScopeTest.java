package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.util.LogPurgeDates;

/**
 * The date purge inherits the page's scope: a project filter narrows to that project and the
 * errors-only filter narrows to failed entries. The cutoff is the start of the day AFTER the
 * selected one, so the selected day itself is included; today is refused outright, which is
 * what keeps "delete by date" from ever becoming a clear-all.
 */
@ExtendWith(MockitoExtension.class)
class ChangeLogServicePurgeScopeTest {

    @Mock private ChangeLogRepository repository;
    @Mock private AdminAuditService auditService;

    private final LocalDate yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1);

    private ChangeLogService service() {
        return new ChangeLogService(repository, auditService);
    }

    private Instant expectedCutoff() {
        return yesterday.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    @Test
    void aScopedPurgePassesTheFilterAndTheInclusiveCutoff() {
        when(repository.purgeBefore(expectedCutoff(), 7L, true)).thenReturn(2);

        int removed = service().purgeBefore(yesterday, 7L, true);

        assertThat(removed).isEqualTo(2);
        verify(repository).purgeBefore(expectedCutoff(), 7L, true);
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE), contains("|2|"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE),
                contains("@failedOnly:true"));
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE),
                contains("@scope:project:7"));
    }

    @Test
    void anUnscopedPurgeCoversEveryProjectAndBothOutcomes() {
        when(repository.purgeBefore(expectedCutoff(), null, false)).thenReturn(5);

        assertThat(service().purgeBefore(yesterday, null, false)).isEqualTo(5);

        verify(repository).purgeBefore(expectedCutoff(), null, false);
        verify(auditService).record(eq(AuditAction.CHANGELOG_PURGE), contains("@scope:all"));
    }

    @Test
    void theCutoffIsTheStartOfTheDayAfterTheSelectedOne() {
        when(repository.purgeBefore(any(), any(), anyBoolean())).thenReturn(0);

        service().purgeBefore(yesterday, null, false);

        // Selected day inclusive: everything strictly before tomorrow-midnight goes.
        verify(repository).purgeBefore(eq(expectedCutoff()), any(), anyBoolean());
    }

    @Test
    void todayIsRefusedBeforeAnythingIsDeleted() {
        assertThatThrownBy(() -> service().purgeBefore(
                LocalDate.now(ZoneId.systemDefault()), null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LogPurgeDates.INVALID_DATE);
        verifyNoInteractions(repository);
    }
}
