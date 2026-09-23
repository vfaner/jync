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
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * {@code sync.changeLogRetentionDays} is a promise the settings make to the user; the pruner
 * is what keeps it. These tests pin both halves of the contract: the retention window is
 * applied, zero disables it, and a failing prune never escapes into the scheduler thread —
 * an exception escaping a fixed-delay task would silently cancel every subsequent run.
 */
@ExtendWith(MockitoExtension.class)
class ChangeLogPrunerTest {

    @Mock private ChangeLogRepository changeLogRepository;

    private SyncProperties properties;
    private ChangeLogPruner pruner;

    @BeforeEach
    void setUp() {
        properties = new SyncProperties();
        pruner = new ChangeLogPruner(changeLogRepository, properties);
    }

    @Test
    void aRetentionOfZeroDisablesPruning() {
        properties.setChangeLogRetentionDays(0);

        pruner.prune();

        verifyNoInteractions(changeLogRepository);
    }

    @Test
    void aPositiveRetentionDeletesEverythingOlderThanTheWindow() {
        properties.setChangeLogRetentionDays(30);
        when(changeLogRepository.deleteOlderThan(any())).thenReturn(0);

        Instant before = Instant.now();
        pruner.prune();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(changeLogRepository).deleteOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isCloseTo(
                before.minus(30, ChronoUnit.DAYS), within(10, ChronoUnit.SECONDS));
    }

    @Test
    void aFailingPruneIsContained() {
        properties.setChangeLogRetentionDays(7);
        when(changeLogRepository.deleteOlderThan(any()))
                .thenThrow(new IllegalStateException("db locked"));

        assertThatCode(() -> pruner.prune()).doesNotThrowAnyException();
    }
}
