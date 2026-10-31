package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.EtlRun;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The dashboard trend chart always draws 24 points, one per hour: a quiet hour is a zero, not a
 * gap. If empty hours were dropped, the newest point would stretch back to whenever traffic last
 * happened and the x-axis would silently lie about "the last 24 hours".
 *
 * <p>The two lines come from different tables on purpose. The sync line is the change log; the
 * ETL line is the ETL run history. They used to share one table with {@code INFO} rows borrowed
 * as the ETL line, which drew ETL traffic for anyone who had never created an ETL task.
 */
class DashboardServiceSeriesTest {

    private final ChangeLogRepository changeLogRepository = mock(ChangeLogRepository.class);
    private final EtlRunRepository etlRunRepository = mock(EtlRunRepository.class);

    private final DashboardService service = new DashboardService(
            mock(ProjectRepository.class), mock(DatabaseConfigRepository.class),
            mock(SyncProgressRepository.class), changeLogRepository, etlRunRepository,
            mock(EtlTaskRepository.class), mock(SyncContextFactory.class), mock(SyncTaskStore.class));

    @Test
    void theSeriesIsTwentyFourConsecutiveHourlyBucketsEndingWithTheCurrentHour() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.of(
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L}));

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.points()).hasSize(24);
        assertThat(series.points().get(23).syncChanges()).isEqualTo(3);
        assertThat(series.points().get(23).syncRows()).isEqualTo(12);
        // 20:00 yesterday is three hours into the window that starts at 11:00.
        assertThat(series.points().get(9).syncChanges()).isEqualTo(1);
        assertThat(series.points().get(9).syncRows()).isEqualTo(4);
    }

    /**
     * ETL activity has its own history table: one row per run, written when the run finishes.
     * The line counts runs and sums the rows they wrote, so a minute with two scheduled runs is
     * "2 次 ETL 任务执行", not two change-log entries of unknown origin.
     */
    @Test
    void theEtlLineCountsRunsAndSumsTheRowsTheyWrote() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(etlRunRepository.findByOccurredAtBetweenOrderByOccurredAtAsc(any(), any()))
                .thenReturn(List.of(
                        run(Instant.parse("2026-01-01T10:05:00Z"), 4),
                        run(Instant.parse("2026-01-01T10:40:00Z"), 6),
                        run(Instant.parse("2026-01-01T08:10:00Z"), null)));

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.points().get(23).etlChanges()).isEqualTo(2);
        assertThat(series.points().get(23).etlRows()).isEqualTo(10);
        assertThat(series.points().get(21).etlChanges()).isEqualTo(1);
        assertThat(series.points().get(21).etlRows()).isZero();
        assertThat(series.totalEtlChanges()).isEqualTo(3);
        assertThat(series.totalEtlRows()).isEqualTo(10);
        assertThat(series.totalSyncChanges()).isZero();
    }

    /**
     * Change-log rows are sync activity whatever their change type: INFO carries the CDC window
     * summaries ("3 upserts, 1 delete") and consumer start/stop notes, and it used to be drawn
     * as the ETL line. It must not move to ETL again, and it must not fall off the chart.
     */
    @Test
    void changeLogRowsStayOnTheSyncLineWhateverTheirChangeType() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");
        when(changeLogRepository.hourlySeriesBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L}));
        when(etlRunRepository.findByOccurredAtBetweenOrderByOccurredAtAsc(any(), any()))
                .thenReturn(List.of());

        DashboardService.MultiSeriesActivity series = service.multiSeriesActivitySeries(now);

        assertThat(series.points().get(23).syncChanges()).isEqualTo(3);
        assertThat(series.points().get(23).etlChanges()).isZero();
        assertThat(series.totalEtlChanges()).isZero();
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
                new Object[] {Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), 3L, 12L},
                new Object[] {Timestamp.from(Instant.parse("2025-12-31T20:00:00Z")), 1L, 4L}));

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

    /** Both lines describe the same 24 hours; the ETL history is read with the chart's window. */
    @Test
    void theEtlHistoryIsReadOverTheSameWindowAsTheChart() {
        Instant now = Instant.parse("2026-01-01T10:25:00Z");

        service.multiSeriesActivitySeries(now);

        org.mockito.Mockito.verify(etlRunRepository)
                .findByOccurredAtBetweenOrderByOccurredAtAsc(
                        Instant.parse("2025-12-31T11:00:00Z"), now);
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

    private static EtlRun run(Instant occurredAt, Integer affectedRows) {
        EtlRun run = new EtlRun();
        run.setEtlTaskId(1L);
        run.setSuccess(true);
        run.setAffectedRows(affectedRows);
        run.setOccurredAt(occurredAt);
        return run;
    }
}
