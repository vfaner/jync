package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.repository.AuditEventRepository;

/**
 * {@code sync.auditRetentionDays} is the audit trail's retention promise; the pruner keeps it.
 * Same contract as the change-log pruner: the window is applied, zero disables it, and a
 * failing prune never escapes into the scheduler thread.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogPrunerTest {

    @Mock private AuditEventRepository auditEventRepository;

    private SyncProperties properties;
    private AuditLogPruner pruner;

    @BeforeEach
    void setUp() {
        properties = new SyncProperties();
        pruner = new AuditLogPruner(auditEventRepository, properties);
    }

    @Test
    void aRetentionOfZeroDisablesPruning() {
        properties.setAuditRetentionDays(0);

        pruner.prune();

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void theDefaultWindowIsTheLongOneAuditReadersExpect() {
        assertThat(properties.getAuditRetentionDays()).isEqualTo(180);
        assertThat(properties.getChangeLogRetentionDays()).isEqualTo(180);
    }

    @Test
    void aPositiveRetentionDeletesEverythingOlderThanTheWindow() {
        properties.setAuditRetentionDays(90);
        when(auditEventRepository.deleteByOccurredAtBefore(any())).thenReturn(0);

        Instant before = Instant.now();
        pruner.prune();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(auditEventRepository).deleteByOccurredAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(
                before.minus(90, ChronoUnit.DAYS).minusSeconds(1),
                after.minus(90, ChronoUnit.DAYS).plusSeconds(1));
    }

    @Test
    void aFailingPruneDoesNotEscapeIntoTheScheduler() {
        properties.setAuditRetentionDays(30);
        when(auditEventRepository.deleteByOccurredAtBefore(any()))
                .thenThrow(new IllegalStateException("store busy"));

        assertThatCode(() -> pruner.prune()).doesNotThrowAnyException();
    }
}
