package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The dashboard trend chart always draws 24 points, one per hour: a quiet hour is a zero, not a
 * gap. If empty hours were dropped, the newest point would stretch back to whenever traffic last
 * happened and the x-axis would silently lie about "the last 24 hours".
 */
class DashboardServiceSeriesTest {

    private final ChangeLogRepository changeLogRepository = mock(ChangeLogRepository.class);

    private final DashboardService service = new DashboardService(
            mock(ProjectRepository.class), mock(DatabaseConfigRepository.class),
            mock(SyncProgressRepository.class), changeLogRepository,
            mock(SyncContextFactory.class), mock(SyncTaskStore.class));

    @Test
    void theSeriesIsTwentyFourConsecutiveHourlyBucketsEndingWithTheCurrentHour() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of(
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L, "INSERT"},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L, "INSERT"}));

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.points()).hasSize(24);
        assertThat(series.points().get(23).syncChanges()).isEqualTo(3);
        assertThat(series.points().get(23).syncRows()).isEqualTo(12);
        // 20:00 yesterday is three hours into the window that starts at 11:00.
        assertThat(series.points().get(9).syncChanges()).isEqualTo(1);
        assertThat(series.points().get(9).syncRows()).isEqualTo(4);
    }

    @Test
    void quietHoursAreFilledWithZeroRatherThanSkipped() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of());

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.points()).hasSize(24);
        assertThat(series.points()).allSatisfy(point -> {
            assertThat(point.syncChanges()).isZero();
            assertThat(point.syncRows()).isZero();
            assertThat(point.etlChanges()).isZero();
            assertThat(point.etlRows()).isZero();
        });
        assertThat(series.totalSyncChanges()).isZero();
        assertThat(series.totalSyncRows()).isZero();
        assertThat(series.totalEtlChanges()).isZero();
        assertThat(series.totalEtlRows()).isZero();
    }

    @Test
    void theTotalsSumTheWholeWindow() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of(
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L, "INSERT"},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L, "INSERT"}));

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.totalSyncChanges()).isEqualTo(4);
        assertThat(series.totalSyncRows()).isEqualTo(16);
        assertThat(series.totalEtlChanges()).isZero();
        assertThat(series.totalEtlRows()).isZero();
    }

    @Test
    void theWindowStartsExactlyTwentyThreeHoursBeforeTheCurrentHour() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");

        service.multiSeriesActivitySeries(now);

        org.mockito.Mockito.verify(changeLogRepository)
                .hourlySeriesBetween(java.sql.Timestamp.from(Instant.parse("2025-12-31T11:00:00Z")),
                        java.sql.Timestamp.from(now));
    }

    /**
     * The tile says "Changes (24h)" and the chart says "Last 24 hours" — a reader will add up the
     * bars and compare. They used to be two different windows: the tile counted from exactly
     * 24 hours ago with no upper bound, the chart from the top of the hour 23 hours back. Any
     * change logged in the first hour of the tile's window (or with a clock-skewed future
     * timestamp) appeared in the tile and in none of the bars.
     */
    @Test
    void theTileCountsExactlyTheWindowTheChartDraws() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        Instant windowStart = Instant.parse("2025-12-31T11:00:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of(
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L, "INSERT"},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L, "INSERT"}));
        when(changeLogRepository.countByOccurredAtBetween(any(), any())).thenReturn(4L);
        when(changeLogRepository.sumAffectedRowsBetween(any(), any())).thenReturn(16L);

        DashboardService.Stats stats = service.collect(now);
        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(stats.getChangesToday()).isEqualTo(series.totalSyncChanges());
        assertThat(stats.getRowsToday()).isEqualTo(series.totalSyncRows());
        org.mockito.Mockito.verify(changeLogRepository)
                .countByOccurredAtBetween(windowStart, now);
        org.mockito.Mockito.verify(changeLogRepository)
                .sumAffectedRowsBetween(windowStart, now);
        org.mockito.Mockito.verify(changeLogRepository)
                .hourlySeriesBetween(Timestamp.from(windowStart), Timestamp.from(now));
    }

    @Test
    void everyPointCarriesAnHourLabel() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of());

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.points()).allSatisfy(point ->
                assertThat(point.label()).matches("\\d{2}:\\d{2}"));
    }
}
