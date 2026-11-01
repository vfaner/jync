package com.qqmu.jync.service.metrics;

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

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;

/**
 * {@code metrics.retention-days} is the promise that keeps the metadata store bounded, now
 * that three tables grow by the minute. A retention of zero or less disables cleanup; each
 * table is pruned on its own, so one failing delete cannot strand the other two.
 */
@ExtendWith(MockitoExtension.class)
class MetricRetentionPrunerTest {

    @Mock private DbMetricSampleRepository dbRepository;
    @Mock private TaskMetricSampleRepository taskRepository;
    @Mock private AppMetricSampleRepository appRepository;

    private MetricsProperties properties;
    private MetricRetentionPruner pruner;

    @BeforeEach
    void setUp() {
        properties = new MetricsProperties();
        pruner = new MetricRetentionPruner(dbRepository, taskRepository, appRepository, properties);
    }

    @Test
    void aRetentionOfZeroDisablesPruning() {
        properties.setRetentionDays(0);

        pruner.prune();

        verifyNoInteractions(dbRepository, taskRepository, appRepository);
    }

    @Test
    void aPositiveRetentionDeletesAllThreeTablesOlderThanTheWindow() {
        properties.setRetentionDays(7);
        when(dbRepository.deleteOlderThan(any())).thenReturn(0);
        when(taskRepository.deleteOlderThan(any())).thenReturn(0);
        when(appRepository.deleteOlderThan(any())).thenReturn(0);

        Instant before = Instant.now();
        pruner.prune();

        Instant expected = before.minus(7, ChronoUnit.DAYS);
        ArgumentCaptor<Instant> dbCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> taskCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> appCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(dbRepository).deleteOlderThan(dbCutoff.capture());
        verify(taskRepository).deleteOlderThan(taskCutoff.capture());
        verify(appRepository).deleteOlderThan(appCutoff.capture());

        assertThat(dbCutoff.getValue()).isCloseTo(expected, within(10, ChronoUnit.SECONDS));
        assertThat(taskCutoff.getValue()).isCloseTo(expected, within(10, ChronoUnit.SECONDS));
        assertThat(appCutoff.getValue()).isCloseTo(expected, within(10, ChronoUnit.SECONDS));
    }

    @Test
    void aFailingTableDoesNotSkipTheOtherTwo() {
        properties.setRetentionDays(7);
        when(dbRepository.deleteOlderThan(any())).thenThrow(new IllegalStateException("db locked"));
        when(taskRepository.deleteOlderThan(any())).thenReturn(0);
        when(appRepository.deleteOlderThan(any())).thenReturn(0);

        assertThatCode(() -> pruner.prune()).doesNotThrowAnyException();

        verify(taskRepository).deleteOlderThan(any());
        verify(appRepository).deleteOlderThan(any());
    }
}
