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
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L}));

        DashboardService.ActivitySeries series = service.activitySeries(now);

        assertThat(series.points()).hasSize(24);
        assertThat(series.points().get(23).changes()).isEqualTo(3);
        assertThat(series.points().get(23).rows()).isEqualTo(12);
        // 20:00 yesterday is three hours into the window that starts at 11:00.
        assertThat(series.points().get(9).changes()).isEqualTo(1);
        assertThat(series.points().get(9).rows()).isEqualTo(4);
    }

    @Test
    void quietHoursAreFilledWithZeroRatherThanSkipped() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of());

        DashboardService.ActivitySeries series = service.activitySeries(now);

        assertThat(series.points()).hasSize(24);
        assertThat(series.points()).allSatisfy(point -> {
            assertThat(point.changes()).isZero();
            assertThat(point.rows()).isZero();
        });
        assertThat(series.totalChanges()).isZero();
        assertThat(series.totalRows()).isZero();
    }

    @Test
    void theTotalsSumTheWholeWindow() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of(
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L}));

        DashboardService.ActivitySeries series = service.activitySeries(now);

        assertThat(series.totalChanges()).isEqualTo(4);
        assertThat(series.totalRows()).isEqualTo(16);
    }

    @Test
    void theWindowStartsExactlyTwentyThreeHoursBeforeTheCurrentHour() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");

        service.activitySeries(now);

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
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L}));
        when(changeLogRepository.countByOccurredAtBetween(any(), any())).thenReturn(4L);
        when(changeLogRepository.sumAffectedRowsBetween(any(), any())).thenReturn(16L);

        DashboardService.Stats stats = service.collect(now);
        DashboardService.ActivitySeries series = service.activitySeries(now);

        assertThat(stats.getChangesToday()).isEqualTo(series.totalChanges());
        assertThat(stats.getRowsToday()).isEqualTo(series.totalRows());
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

        DashboardService.ActivitySeries series = service.activitySeries(now);

        assertThat(series.points()).allSatisfy(point ->
                assertThat(point.label()).matches("\\d{2}:\\d{2}"));
    }
}
