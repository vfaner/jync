package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.AppMetricSample;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.model.TaskMetricSample;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;

/**
 * What the charts are allowed to claim. Two rules carry most of the weight: a window with no
 * samples is null rather than zero, and a "current" figure comes from the newest sample even
 * when that sample says "unknown" — a stale number presented as current is a lie the chart
 * cannot correct.
 */
class MetricsQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DbMetricSampleRepository dbSamples = mock(DbMetricSampleRepository.class);
    private final TaskMetricSampleRepository taskSamples = mock(TaskMetricSampleRepository.class);
    private final AppMetricSampleRepository appSamples = mock(AppMetricSampleRepository.class);
    private final ChangeLogRepository changeLogs = mock(ChangeLogRepository.class);
    private final MetricsQueryService service =
            new MetricsQueryService(dbSamples, taskSamples, appSamples, changeLogs, CLOCK);

    @Test
    void emptyWindowsAreNullPointsNotZeros() {
        givenTasks(taskSample("2026-11-01T11:59:30Z", 10L, 100L, 0, "RUNNING"));

        ChartSeries rows = series(service.project(7L, MetricsRange.LAST_HOUR), "rows");

        assertThat(rows.points()).hasSize(60);
        assertThat(rows.points().get(59).value()).isEqualTo(10.0);
        assertThat(rows.points().subList(0, 59))
                .allSatisfy(point -> assertThat(point.value()).isNull());
    }

    @Test
    void theRangeSelectsTheBucketSize() {
        assertThat(series(service.project(7L, MetricsRange.LAST_HOUR), "rows").points())
                .hasSize(60);
        assertThat(series(service.project(7L, MetricsRange.LAST_DAY), "rows").points())
                .hasSize(144);
        assertThat(series(service.project(7L, MetricsRange.LAST_WEEK), "rows").points())
                .hasSize(168);
    }

    @Test
    void anUnknownRangeFallsBackToADay() {
        assertThat(MetricsRange.parse("1h")).isEqualTo(MetricsRange.LAST_HOUR);
        assertThat(MetricsRange.parse("7d")).isEqualTo(MetricsRange.LAST_WEEK);
        assertThat(MetricsRange.parse("nonsense")).isEqualTo(MetricsRange.LAST_DAY);
        assertThat(MetricsRange.parse(null)).isEqualTo(MetricsRange.LAST_DAY);
    }

    @Test
    void uptimeIsNullWhenThereAreNoSamples() {
        DbMetricsView view = service.database(3L, MetricsRange.LAST_DAY);

        assertThat(view.uptimePercent()).isNull();
        assertThat(view.lastSample()).isNull();
        assertThat(view.series()).isNotEmpty();
    }

    @Test
    void allFailedSamplesGiveZeroUptimeRatherThanADivisionByZero() {
        givenDb(dbSample("2026-11-01T11:55:00Z", false, null, null, "连接被拒绝"),
                dbSample("2026-11-01T12:00:00Z", false, null, null, "连接被拒绝"));

        DbMetricsView view = service.database(3L, MetricsRange.LAST_HOUR);

        assertThat(view.uptimePercent()).isEqualTo(0.0);
        assertThat(view.ok()).isFalse();
        assertThat(view.message()).isEqualTo("连接被拒绝");
    }

    @Test
    void theLatestFigureIsNullWhenTheNewestSampleSaysUnknown() {
        givenDb(dbSample("2026-11-01T11:58:00Z", true, 9, 2.0, null),
                dbSample("2026-11-01T12:00:00Z", true, null, null, null));

        DbMetricsView view = service.database(3L, MetricsRange.LAST_HOUR);

        assertThat(view.current().get("sessions")).isNull();
    }

    @Test
    void capacityFallsBackToTheLastSampleThatReportedIt() {
        DbMetricSample older = dbSample("2026-11-01T11:05:00Z", true, 4, 1.0, null);
        older.setTableCount(12);
        older.setRowCount(900L);
        older.setBytes(1024L);
        givenDb(older, dbSample("2026-11-01T12:00:00Z", true, 4, 1.0, null));

        DbMetricsView view = service.database(3L, MetricsRange.LAST_HOUR);

        assertThat(view.current().get("bytes")).isEqualTo(1024.0);
        assertThat(view.current().get("rowCount")).isEqualTo(900.0);
        assertThat(view.current().get("tableCount")).isEqualTo(12.0);
    }

    @Test
    /**
     * 项目页只讲这个任务自己的数；进程资源是整台机器的，只在看板那个接口上出现。
     * 若这里还混着 CPU / 堆，看板上的管理员限制就形同虚设。
     */
    void theProjectViewNeverCarriesTheSharedProcessCurves() {
        AppMetricSample app = new AppMetricSample();
        app.setSampledAt(Instant.parse("2026-11-01T11:59:30Z"));
        app.setCpuPercent(12.5);
        app.setHeapUsed(256L);
        app.setHeapMax(1024L);
        app.setThreads(40);
        when(appSamples.findBySampledAtAfterOrderBySampledAtAsc(any())).thenReturn(List.of(app));

        ProjectMetricsView view = service.project(7L, MetricsRange.LAST_HOUR);

        assertThat(view.series()).extracting(ChartSeries::key)
                .doesNotContain("cpu", "heap", "threads", "gcTime");
        assertThat(view.current()).doesNotContainKeys("cpu", "heap", "threads", "gcCount");
    }

    @Test
    void theProcessCurvesComeFromTheSamplesEveryTaskShares() {
        AppMetricSample app = new AppMetricSample();
        app.setSampledAt(Instant.parse("2026-11-01T11:59:30Z"));
        app.setCpuPercent(12.5);
        app.setHeapUsed(256L);
        app.setHeapMax(1024L);
        app.setThreads(40);
        app.setGcTimeMs(7L);
        when(appSamples.findBySampledAtAfterOrderBySampledAtAsc(any())).thenReturn(List.of(app));

        AppMetricsView view = service.app(MetricsRange.LAST_HOUR);

        assertThat(view.series()).extracting(ChartSeries::key)
                .containsExactly("cpu", "heap", "threads", "gcTime");
        assertThat(view.current()).containsEntry("cpu", 12.5)
                .containsEntry("threads", 40.0)
                .containsEntry("gcTime", 7.0);
        assertThat(view.lastSample()).isEqualTo(Instant.parse("2026-11-01T11:59:30Z"));
    }

    @Test
    void changeCountsFeedTheCurveTheDonutAndTheHotTable() {
        java.sql.Timestamp bucket = java.sql.Timestamp.from(Instant.parse("2026-11-01T11:59:00Z"));
        when(changeLogs.minutelyChangesForProject(eq(7L), any(), any())).thenReturn(List.of(
                new Object[] {bucket, "INSERT", 3L},
                new Object[] {bucket, "UPDATE", 1L}));
        when(changeLogs.topChangedObjects(eq(7L), any(), any(), any())).thenReturn(
                List.<Object[]>of(new Object[] {"orders", 7L}));

        ProjectMetricsView view = service.project(7L, MetricsRange.LAST_HOUR);

        assertThat(series(view, "insert").points().get(59).value()).isEqualTo(3.0);
        assertThat(series(view, "update").points().get(59).value()).isEqualTo(1.0);
        assertThat(series(view, "delete").points().get(59).value()).isNull();
        assertThat(view.changeShare()).extracting(SeriesPoint::label)
                .containsExactlyInAnyOrder("INSERT", "UPDATE");
        assertThat(view.changeShare()).filteredOn(p -> p.label().equals("INSERT"))
                .extracting(SeriesPoint::value).containsExactly(3.0);
        assertThat(view.hotObjects()).singleElement()
                .satisfies(point -> {
                    assertThat(point.label()).isEqualTo("orders");
                    assertThat(point.value()).isEqualTo(7.0);
                });
    }

    private ChartSeries series(ProjectMetricsView view, String key) {
        return view.series().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no series " + key + " in "
                        + view.series().stream().map(ChartSeries::key).toList()));
    }

    private void givenTasks(TaskMetricSample... samples) {
        when(taskSamples.findByProjectIdAndSampledAtAfterOrderBySampledAtAsc(eq(7L), any()))
                .thenReturn(List.of(samples));
    }

    private void givenDb(DbMetricSample... samples) {
        when(dbSamples.findByConnectionIdAndSampledAtAfterOrderBySampledAtAsc(eq(3L), any()))
                .thenReturn(List.of(samples));
    }

    private TaskMetricSample taskSample(String at, Long delta, Long total, int failures,
                                        String status) {
        TaskMetricSample sample = new TaskMetricSample();
        sample.setProjectId(7L);
        sample.setSampledAt(Instant.parse(at));
        sample.setRowsDelta(delta);
        sample.setRowsTotal(total);
        sample.setLagMs(0L);
        sample.setConsecutiveFailures(failures);
        sample.setStatus(status);
        return sample;
    }

    private DbMetricSample dbSample(String at, boolean ok, Integer sessions, Double qps,
                                    String message) {
        DbMetricSample sample = new DbMetricSample();
        sample.setConnectionId(3L);
        sample.setSampledAt(Instant.parse(at));
        sample.setOk(ok);
        sample.setLatencyMs(ok ? 12L : null);
        sample.setSessions(sessions);
        sample.setQps(qps);
        sample.setMessage(message);
        return sample;
    }
}
