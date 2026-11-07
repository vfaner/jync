package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.AppMetricSample;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.model.TaskMetricSample;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;

/**
 * The Prometheus scrape body: latest stored samples, one series per project/connection, and
 * nulls as {@code NaN} so a scraper can tell "not reported" from zero.
 */
class PrometheusMetricsServiceTest {

    private static final Instant SAMPLED_AT = Instant.parse("2026-11-01T10:00:00Z");

    private final DbMetricSampleRepository dbSamples = mock(DbMetricSampleRepository.class);
    private final TaskMetricSampleRepository taskSamples =
            mock(TaskMetricSampleRepository.class);
    private final AppMetricSampleRepository appSamples = mock(AppMetricSampleRepository.class);
    private final DatabaseConfigRepository configRepository =
            mock(DatabaseConfigRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);

    private PrometheusMetricsService service;

    @BeforeEach
    void setUp() {
        service = new PrometheusMetricsService(dbSamples, taskSamples, appSamples,
                configRepository, projectRepository);
    }

    @Test
    void theProcessSampleIsExposedAsGauges() {
        AppMetricSample app = new AppMetricSample();
        app.setSampledAt(SAMPLED_AT);
        app.setCpuPercent(12.5);
        app.setHeapUsed(1024L);
        app.setHeapMax(2048L);
        app.setNonheapUsed(512L);
        app.setThreads(40);
        app.setGcCount(7L);
        app.setGcTimeMs(900L);
        app.setSysLoadPercent(3.0);
        app.setSysMemPercent(55.0);
        app.setDiskPercent(61.0);
        when(appSamples.findTopByOrderBySampledAtDesc()).thenReturn(Optional.of(app));

        String body = service.scrape();

        assertThat(body)
                .contains("jync_up 1")
                .contains("jync_app_cpu_ratio 0.125")
                .contains("jync_app_heap_used_bytes 1024")
                .contains("jync_app_heap_max_bytes 2048")
                .contains("jync_app_nonheap_used_bytes 512")
                .contains("jync_app_threads 40")
                .contains("jync_app_gc_count_total 7")
                .contains("jync_app_gc_time_seconds_total 0.9")
                .contains("jync_app_system_load_ratio 0.03")
                .contains("jync_app_system_memory_ratio 0.55")
                .contains("jync_app_disk_usage_ratio 0.61")
                .contains("jync_app_last_sample_timestamp_seconds 1793527200");
    }

    @Test
    void eachTaskGetsItsCurrentFigures() {
        namedProject(1L, "orders");
        TaskMetricSample sample = new TaskMetricSample();
        sample.setProjectId(1L);
        sample.setSampledAt(SAMPLED_AT);
        sample.setRowsTotal(160L);
        sample.setRowsDelta(60L);
        sample.setLagMs(2_000L);
        sample.setConsecutiveFailures(0);
        sample.setStatus("RUNNING");
        when(taskSamples.findLatestPerProject()).thenReturn(List.of(sample));

        String body = service.scrape();

        assertThat(body)
                .contains("jync_task_rows_total{project=\"orders\",project_id=\"1\"} 160")
                .contains("jync_task_rows_delta{project=\"orders\",project_id=\"1\"} 60")
                .contains("jync_task_lag_seconds{project=\"orders\",project_id=\"1\"} 2.0")
                .contains("jync_task_consecutive_failures{project=\"orders\",project_id=\"1\"} 0")
                .contains("jync_task_status_info{project=\"orders\",project_id=\"1\","
                        + "status=\"RUNNING\"} 1")
                .contains("jync_task_last_sample_timestamp_seconds"
                        + "{project=\"orders\",project_id=\"1\"} 1793527200");
    }

    @Test
    void anUnknownFigureIsNanNotZero() {
        namedProject(1L, "orders");
        TaskMetricSample sample = new TaskMetricSample();
        sample.setProjectId(1L);
        sample.setSampledAt(SAMPLED_AT);
        when(taskSamples.findLatestPerProject()).thenReturn(List.of(sample));

        String body = service.scrape();

        assertThat(body)
                .contains("jync_task_rows_total{project=\"orders\",project_id=\"1\"} NaN")
                .contains("jync_task_lag_seconds{project=\"orders\",project_id=\"1\"} NaN")
                .doesNotContain("jync_task_status_info");
    }

    @Test
    void eachConnectionGetsItsLatestProbe() {
        namedConnection(10L, "prod-mysql");
        DbMetricSample sample = new DbMetricSample();
        sample.setConnectionId(10L);
        sample.setSampledAt(SAMPLED_AT);
        sample.setOk(true);
        sample.setLatencyMs(5L);
        sample.setSessions(12);
        sample.setQps(100.0);
        sample.setCacheHitPercent(98.5);
        sample.setLockWaits(2);
        sample.setTableCount(9);
        sample.setRowCount(1_000L);
        sample.setBytes(4_096L);
        when(dbSamples.findLatestPerConnection()).thenReturn(List.of(sample));

        String body = service.scrape();

        String labels = "{connection=\"prod-mysql\",connection_id=\"10\"}";
        assertThat(body)
                .contains("jync_connection_healthy" + labels + " 1")
                .contains("jync_connection_latency_seconds" + labels + " 0.005")
                .contains("jync_connection_sessions" + labels + " 12")
                .contains("jync_connection_qps" + labels + " 100.0")
                .contains("jync_connection_cache_hit_ratio" + labels + " 0.985")
                .contains("jync_connection_lock_waits" + labels + " 2")
                .contains("jync_connection_table_count" + labels + " 9")
                .contains("jync_connection_rows_total" + labels + " 1000")
                .contains("jync_connection_bytes" + labels + " 4096");
    }

    @Test
    void aFailedProbeReportsNoLatency() {
        // A probe that could not connect still carries the time it spent failing. Exporting
        // that as latency makes the scrape claim a 30 s round-trip to a database that never
        // answered — an alert rule on latency then fires for the wrong reason.
        namedConnection(10L, "prod-mysql");
        DbMetricSample sample = new DbMetricSample();
        sample.setConnectionId(10L);
        sample.setSampledAt(SAMPLED_AT);
        sample.setOk(false);
        sample.setLatencyMs(30_000L);
        when(dbSamples.findLatestPerConnection()).thenReturn(List.of(sample));

        String body = service.scrape();

        String labels = "{connection=\"prod-mysql\",connection_id=\"10\"}";
        assertThat(body)
                .contains("jync_connection_healthy" + labels + " 0")
                .contains("jync_connection_latency_seconds" + labels + " NaN");
    }

    @Test
    void labelValuesAreEscaped() {
        namedProject(1L, "a\"b\\c\nd");
        TaskMetricSample sample = new TaskMetricSample();
        sample.setProjectId(1L);
        sample.setSampledAt(SAMPLED_AT);
        when(taskSamples.findLatestPerProject()).thenReturn(List.of(sample));

        String body = service.scrape();

        assertThat(body).contains(
                "project=\"a\\\"b\\\\c\\nd\"");
    }

    @Test
    void noSamplesStillReportsTheProcessAsUp() {
        when(appSamples.findTopByOrderBySampledAtDesc()).thenReturn(Optional.empty());
        when(taskSamples.findLatestPerProject()).thenReturn(List.of());
        when(dbSamples.findLatestPerConnection()).thenReturn(List.of());

        String body = service.scrape();

        assertThat(body).contains("jync_up 1");
        assertThat(body).doesNotContain("jync_app_cpu_ratio");
    }

    @Test
    void theLabelMapsDoNotLoadWholeEntities() {
        // A scrape runs every 15 seconds. Project carries the serialized sync config and
        // DatabaseConfig carries encrypted credentials, so reading whole rows to get two
        // label values is pure waste; the id/name projection is the only query allowed.
        when(projectRepository.findAllIdAndName()).thenReturn(List.of());
        when(configRepository.findAllIdAndName()).thenReturn(List.of());
        when(taskSamples.findLatestPerProject()).thenReturn(List.of());
        when(dbSamples.findLatestPerConnection()).thenReturn(List.of());

        service.scrape();

        verify(projectRepository, never()).findAll();
        verify(configRepository, never()).findAll();
    }

    private void namedProject(long id, String name) {
        when(projectRepository.findAllIdAndName())
                .thenReturn(List.of(new Named(id, name)));
    }

    private void namedConnection(long id, String name) {
        when(configRepository.findAllIdAndName())
                .thenReturn(List.of(new Named(id, name)));
    }

    /** The two projections share a shape; one record stands in for both in the mocks. */
    private record Named(Long id, String name)
            implements ProjectRepository.IdAndName, DatabaseConfigRepository.IdAndName {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
