package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The dashboard used to count only real-time projects; ETL is a second job family and its tasks
 * were invisible on the overview. The tile pair must count ETL tasks the same way the real-time
 * tile counts projects: the total on top, the enabled (scheduled) share underneath.
 */
class DashboardEtlStatsTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final EtlTaskRepository etlTaskRepository = mock(EtlTaskRepository.class);
    private final EtlBatchRepository etlBatchRepository = mock(EtlBatchRepository.class);

    private final DashboardService service = new DashboardService(
            projectRepository, mock(DatabaseConfigRepository.class),
            mock(SyncProgressRepository.class), mock(ChangeLogRepository.class),
            mock(EtlRunRepository.class), etlTaskRepository, etlBatchRepository,
            mock(SyncContextFactory.class), mock(SyncTaskStore.class));

    @Test
    void theTilesCountEtlTasksAndTheNumberOfEnabledOnes() {
        when(projectRepository.findAll(any(Sort.class))).thenReturn(List.of());
        when(etlTaskRepository.count()).thenReturn(5L);
        when(etlTaskRepository.countByEnabledTrue()).thenReturn(2L);

        DashboardService.Stats stats = service.collect(Instant.parse("2026-01-01T10:25:00Z"));

        assertThat(stats.getEtlTaskCount()).isEqualTo(5);
        assertThat(stats.getEnabledEtlTaskCount()).isEqualTo(2);
    }

    /** An ETL-only installation still reports zero real-time projects — the two counts stay apart. */
    @Test
    void etlCountsDoNotLeakIntoTheRealTimeTaskCount() {
        when(projectRepository.findAll(any(Sort.class))).thenReturn(List.of());
        when(projectRepository.count()).thenReturn(0L);
        when(projectRepository.countByEnabledTrue()).thenReturn(0L);
        when(etlTaskRepository.count()).thenReturn(3L);
        when(etlTaskRepository.countByEnabledTrue()).thenReturn(3L);

        DashboardService.Stats stats = service.collect(Instant.parse("2026-01-01T10:25:00Z"));

        assertThat(stats.getProjectCount()).isZero();
        assertThat(stats.getEnabledProjectCount()).isZero();
        assertThat(stats.getEtlTaskCount()).isEqualTo(3);
    }

    /** An enabled batch is one scheduled job, even though its member rows are disabled. */
    @Test
    void enabledBatchesCountAsScheduledEtlJobs() {
        when(projectRepository.findAll(any(Sort.class))).thenReturn(List.of());
        when(etlTaskRepository.countByEnabledTrue()).thenReturn(1L);
        when(etlBatchRepository.countByEnabledTrue()).thenReturn(2L);

        DashboardService.Stats stats = service.collect(Instant.parse("2026-01-01T10:25:00Z"));

        assertThat(stats.getEnabledEtlTaskCount()).isEqualTo(3);
    }
}
