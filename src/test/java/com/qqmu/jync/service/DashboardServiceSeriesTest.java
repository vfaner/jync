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
        when(changeLogRepository.hourlySeriesSince(any())).thenReturn(List.of(
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
        when(changeLogRepository.hourlySeriesSince(any())).thenReturn(List.of());

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
        when(changeLogRepository.hourlySeriesSince(any())).thenReturn(List.of(
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
                .hourlySeriesSince(java.sql.Timestamp.from(Instant.parse("2025-12-31T11:00:00Z")));
    }

    @Test
    void everyPointCarriesAnHourLabel() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesSince(any())).thenReturn(List.of());

        DashboardService.ActivitySeries series = service.activitySeries(now);

        assertThat(series.points()).allSatisfy(point ->
                assertThat(point.label()).matches("\\d{2}:\\d{2}"));
    }
}
