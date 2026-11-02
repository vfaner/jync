package com.qqmu.jync.service.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AppMetricSample;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.TaskMetricSample;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;

/**
 * Renders the latest stored samples as a Prometheus text exposition.
 *
 * <p>It reads the sample tables instead of dialling the databases: a scrape every 15 seconds
 * must not open a session on every configured production server, and the stored rows are the
 * same figures the performance pages show. A null figure is written as {@code NaN} rather
 * than zero — "this product does not report sessions" and "zero sessions" are different
 * answers, and a dashboard must not chart the first as the second.
 *
 * <p>Only the newest row per project and per connection is exposed. The series are gauges of
 * current state, not a history: history belongs to the performance pages, whose retention is
 * measured in days, while Prometheus keeps its own.
 */
@Service
public class PrometheusMetricsService {

    private static final String TEXT_FORMAT = "# HELP %s %s\n# TYPE %s %s\n";

    private final DbMetricSampleRepository dbSamples;
    private final TaskMetricSampleRepository taskSamples;
    private final AppMetricSampleRepository appSamples;
    private final DatabaseConfigRepository configRepository;
    private final ProjectRepository projectRepository;

    public PrometheusMetricsService(DbMetricSampleRepository dbSamples,
                                    TaskMetricSampleRepository taskSamples,
                                    AppMetricSampleRepository appSamples,
                                    DatabaseConfigRepository configRepository,
                                    ProjectRepository projectRepository) {
        this.dbSamples = dbSamples;
        this.taskSamples = taskSamples;
        this.appSamples = appSamples;
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
    }

    public String scrape() {
        StringBuilder out = new StringBuilder(8192);
        heading(out, "jync_up", "gauge",
                "1 while the Jync process is answering scrapes.");
        sample(out, "jync_up", "", "1");
        appendApp(out);
        appendTasks(out);
        appendConnections(out);
        return out.toString();
    }

    private void appendApp(StringBuilder out) {
        Optional<AppMetricSample> latest = appSamples.findTopByOrderBySampledAtDesc();
        if (latest.isEmpty()) {
            return;
        }
        AppMetricSample sample = latest.get();
        heading(out, "jync_app_cpu_ratio", "gauge", "CPU used by the Jync process (0..1).");
        sample(out, "jync_app_cpu_ratio", "", ratio(sample.getCpuPercent()));
        heading(out, "jync_app_heap_used_bytes", "gauge", "JVM heap in use.");
        sample(out, "jync_app_heap_used_bytes", "", number(sample.getHeapUsed()));
        heading(out, "jync_app_heap_max_bytes", "gauge", "JVM maximum heap.");
        sample(out, "jync_app_heap_max_bytes", "", number(sample.getHeapMax()));
        heading(out, "jync_app_nonheap_used_bytes", "gauge", "JVM non-heap memory in use.");
        sample(out, "jync_app_nonheap_used_bytes", "", number(sample.getNonheapUsed()));
        heading(out, "jync_app_threads", "gauge", "Live JVM threads.");
        sample(out, "jync_app_threads", "", number(sample.getThreads()));
        heading(out, "jync_app_gc_count_total", "counter", "GC collections since JVM start.");
        sample(out, "jync_app_gc_count_total", "", number(sample.getGcCount()));
        heading(out, "jync_app_gc_time_seconds_total", "counter",
                "GC time since JVM start.");
        sample(out, "jync_app_gc_time_seconds_total", "", seconds(sample.getGcTimeMs()));
        heading(out, "jync_app_system_load_ratio", "gauge", "Host CPU load (0..1).");
        sample(out, "jync_app_system_load_ratio", "", ratio(sample.getSysLoadPercent()));
        heading(out, "jync_app_system_memory_ratio", "gauge", "Host memory in use (0..1).");
        sample(out, "jync_app_system_memory_ratio", "", ratio(sample.getSysMemPercent()));
        heading(out, "jync_app_disk_usage_ratio", "gauge", "Host disk in use (0..1).");
        sample(out, "jync_app_disk_usage_ratio", "", ratio(sample.getDiskPercent()));
        heading(out, "jync_app_last_sample_timestamp_seconds", "gauge",
                "When the process sample was taken.");
        sample(out, "jync_app_last_sample_timestamp_seconds", "",
                epochSeconds(sample.getSampledAt()));
    }

    private void appendTasks(StringBuilder out) {
        Map<Long, String> names = projectNames();
        List<TaskMetricSample> samples = new ArrayList<>(taskSamples.findLatestPerProject());
        samples.sort(Comparator.comparing(TaskMetricSample::getProjectId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        if (samples.isEmpty()) {
            return;
        }

        heading(out, "jync_task_rows_total", "gauge",
                "Rows synced by the project as of its last sample.");
        heading(out, "jync_task_rows_delta", "gauge",
                "Rows added since the previous sample.");
        heading(out, "jync_task_lag_seconds", "gauge",
                "Replication lag of the task, or NaN while unknown.");
        heading(out, "jync_task_consecutive_failures", "gauge",
                "Consecutive failed cycles.");
        heading(out, "jync_task_last_sample_timestamp_seconds", "gauge",
                "When the task sample was taken.");
        if (samples.stream().anyMatch(sample -> sample.getStatus() != null)) {
            heading(out, "jync_task_status_info", "gauge",
                    "Current task status, one series per observed status.");
        }
        for (TaskMetricSample sample : samples) {
            if (sample.getProjectId() == null) {
                continue;
            }
            String labels = labels(sample.getProjectId(), names.get(sample.getProjectId()));
            sample(out, "jync_task_rows_total", labels, number(sample.getRowsTotal()));
            sample(out, "jync_task_rows_delta", labels, number(sample.getRowsDelta()));
            sample(out, "jync_task_lag_seconds", labels, seconds(sample.getLagMs()));
            sample(out, "jync_task_consecutive_failures", labels,
                    number(sample.getConsecutiveFailures()));
            sample(out, "jync_task_last_sample_timestamp_seconds", labels,
                    epochSeconds(sample.getSampledAt()));
            if (sample.getStatus() != null) {
                sample(out, "jync_task_status_info",
                        labels + "," + label("status", sample.getStatus()), "1");
            }
        }
    }

    private void appendConnections(StringBuilder out) {
        Map<Long, String> names = connectionNames();
        List<DbMetricSample> samples = new ArrayList<>(dbSamples.findLatestPerConnection());
        samples.sort(Comparator.comparing(DbMetricSample::getConnectionId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        if (samples.isEmpty()) {
            return;
        }

        heading(out, "jync_connection_healthy", "gauge",
                "1 when the last probe reached the connection, 0 otherwise.");
        heading(out, "jync_connection_latency_seconds", "gauge",
                "Round-trip of the last health probe.");
        heading(out, "jync_connection_sessions", "gauge", "Sessions on the server.");
        heading(out, "jync_connection_qps", "gauge", "Statements per second.");
        heading(out, "jync_connection_cache_hit_ratio", "gauge",
                "Buffer-pool cache hit ratio (0..1).");
        heading(out, "jync_connection_lock_waits", "gauge", "Current lock waits.");
        heading(out, "jync_connection_table_count", "gauge", "Tables in the schema.");
        heading(out, "jync_connection_rows_total", "gauge",
                "Estimated rows across the schema.");
        heading(out, "jync_connection_bytes", "gauge", "Estimated bytes across the schema.");
        heading(out, "jync_connection_last_sample_timestamp_seconds", "gauge",
                "When the connection probe was taken.");
        for (DbMetricSample sample : samples) {
            if (sample.getConnectionId() == null) {
                continue;
            }
            String labels = connectionLabels(sample.getConnectionId(),
                    names.get(sample.getConnectionId()));
            sample(out, "jync_connection_healthy", labels, sample.isOk() ? "1" : "0");
            // A failed probe still timed itself; exporting that as latency would claim a
            // round-trip to a database that never answered. The healthy gauge already says
            // the probe failed, so latency is unknown.
            sample(out, "jync_connection_latency_seconds", labels,
                    sample.isOk() ? seconds(sample.getLatencyMs()) : "NaN");
            sample(out, "jync_connection_sessions", labels, number(sample.getSessions()));
            sample(out, "jync_connection_qps", labels, number(sample.getQps()));
            sample(out, "jync_connection_cache_hit_ratio", labels,
                    ratio(sample.getCacheHitPercent()));
            sample(out, "jync_connection_lock_waits", labels, number(sample.getLockWaits()));
            sample(out, "jync_connection_table_count", labels, number(sample.getTableCount()));
            sample(out, "jync_connection_rows_total", labels, number(sample.getRowCount()));
            sample(out, "jync_connection_bytes", labels, number(sample.getBytes()));
            sample(out, "jync_connection_last_sample_timestamp_seconds", labels,
                    epochSeconds(sample.getSampledAt()));
        }
    }

    private Map<Long, String> projectNames() {
        Map<Long, String> names = new HashMap<>();
        for (Project project : projectRepository.findAll()) {
            if (project.getId() != null) {
                names.put(project.getId(), project.getName());
            }
        }
        return names;
    }

    private Map<Long, String> connectionNames() {
        Map<Long, String> names = new HashMap<>();
        for (DatabaseConfig config : configRepository.findAll()) {
            if (config.getId() != null) {
                names.put(config.getId(), config.getName());
            }
        }
        return names;
    }

    private static String labels(Long projectId, String name) {
        return label("project", name) + "," + label("project_id", projectId);
    }

    private static String connectionLabels(Long connectionId, String name) {
        return label("connection", name) + "," + label("connection_id", connectionId);
    }

    private static void heading(StringBuilder out, String name, String type, String help) {
        out.append(String.format(TEXT_FORMAT, name, help, name, type));
    }

    private static void sample(StringBuilder out, String name, String labels, String value) {
        out.append(name);
        if (!labels.isEmpty()) {
            out.append('{').append(labels).append('}');
        }
        out.append(' ').append(value).append('\n');
    }

    /** Prometheus has no null; NaN is its "unknown", and the one a graph must not read as 0. */
    private static String number(Number value) {
        return value == null ? "NaN" : value.toString();
    }

    private static String ratio(Number percent) {
        return percent == null ? "NaN" : decimal(percent.doubleValue() / 100.0);
    }

    private static String seconds(Number millis) {
        return millis == null ? "NaN" : decimal(millis.doubleValue() / 1000.0);
    }

    private static String epochSeconds(Instant instant) {
        return instant == null ? "NaN" : decimal(instant.toEpochMilli() / 1000.0);
    }

    /**
     * Plain decimal notation. {@link Double#toString} switches to scientific notation from
     * 10^7, which would turn an epoch timestamp into {@code 1.7620776E9} — not a number the
     * Prometheus parser accepts.
     */
    private static String decimal(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).toPlainString() : "NaN";
    }

    private static String label(String name, Object value) {
        return name + "=\"" + escapeLabel(value == null ? null : String.valueOf(value)) + "\"";
    }

    /** Prometheus text format: backslash and quote escape, newline becomes {@code \n}. */
    static String escapeLabel(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    // A raw CR would split the exposition line for some parsers.
                    break;
                default:
                    escaped.append(c < 0x20 ? ' ' : c);
            }
        }
        return escaped.toString();
    }
}
