package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.repository.AuditEventRepository;
import com.qqmu.jync.util.LogPurgeDates;

/**
 * The audit trail's single delete: an administrator's date purge. It must remove only rows
 * before the cutoff, and — because the rows it removes are gone — record itself with the real
 * count and the cutoff day, so the trim stays accountable.
 */
@ExtendWith(MockitoExtension.class)
class AdminAuditServicePurgeTest {

    @Mock private AuditEventRepository repository;

    private final LocalDate yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1);

    @Test
    void thePurgeDeletesBeforeTheCutoffAndAuditsItself() {
        Instant cutoff = yesterday.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        when(repository.deleteByOccurredAtBefore(cutoff)).thenReturn(9);
        AdminAuditService service = new AdminAuditService(repository);

        int removed = service.purgeBefore(yesterday);

        assertThat(removed).isEqualTo(9);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent trail = captor.getValue();
        assertThat(trail.getAction()).isEqualTo(AuditAction.AUDIT_PURGE);
        assertThat(trail.getDetail()).contains("|9|").endsWith("|" + yesterday);
    }

    @Test
    void aTooNewDayIsRefusedWithoutTouchingTheTrail() {
        AdminAuditService service = new AdminAuditService(repository);

        assertThatThrownBy(() -> service.purgeBefore(LocalDate.now(ZoneId.systemDefault())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LogPurgeDates.INVALID_DATE);
        verify(repository, org.mockito.Mockito.never()).deleteByOccurredAtBefore(any());
    }
}
