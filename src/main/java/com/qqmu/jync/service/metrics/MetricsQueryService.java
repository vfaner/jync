package com.qqmu.jync.service.metrics;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AppMetricSample;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.model.TaskMetricSample;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;

/**
 * Turns the stored samples into the two pages' chart data.
 *
 * <p>Bucketing happens here, in memory: a week of samples is at most a few tens of thousands
 * of rows, and a ten-second refresh over that is cheaper than teaching the H2 metadata store
 * a second set of time-bucketing SQL. The change log is the exception — it can be large, so
 * its per-minute totals are grouped in the database and only the totals travel.
 */
@Service
public class MetricsQueryService {

    /** How many rows the hottest-table ranking shows. */
    private static final int HOT_OBJECT_LIMIT = 10;

    private static final List<ChangeType> CHANGE_ORDER =
            List.of(ChangeType.INSERT, ChangeType.UPDATE, ChangeType.DELETE);

    private final DbMetricSampleRepository dbSamples;
    private final TaskMetricSampleRepository taskSamples;
    private final AppMetricSampleRepository appSamples;
    private final ChangeLogRepository changeLogs;
    private final Clock clock;

    @Autowired
    public MetricsQueryService(DbMetricSampleRepository dbSamples,
                               TaskMetricSampleRepository taskSamples,
                               AppMetricSampleRepository appSamples,
                               ChangeLogRepository changeLogs) {
        this(dbSamples, taskSamples, appSamples, changeLogs, Clock.systemUTC());
    }

    MetricsQueryService(DbMetricSampleRepository dbSamples,
                        TaskMetricSampleRepository taskSamples,
                        AppMetricSampleRepository appSamples,
                        ChangeLogRepository changeLogs, Clock clock) {
        this.dbSamples = dbSamples;
        this.taskSamples = taskSamples;
        this.appSamples = appSamples;
        this.changeLogs = changeLogs;
        this.clock = clock;
    }

    public DbMetricsView database(long connectionId, MetricsRange range) {
        Instant to = clock.instant();
        Instant from = to.minus(range.span());
        List<DbMetricSample> samples = dbSamples
                .findByConnectionIdAndSampledAtAfterOrderBySampledAtAsc(connectionId, from);

        List<ChartSeries> series = List.of(
                series("latency", "Latency (ms)", from, to, range.bucket(), samples,
                        DbMetricSample::getSampledAt,
                        sample -> sample.isOk() ? number(sample.getLatencyMs()) : null,
                        MetricBucket::average),
                series("sessions", "Sessions", from, to, range.bucket(), samples,
                        DbMetricSample::getSampledAt,
                        sample -> number(sample.getSessions()), MetricBucket::average),
                series("qps", "QPS", from, to, range.bucket(), samples,
                        DbMetricSample::getSampledAt,
                        DbMetricSample::getQps, MetricBucket::average),
                series("cacheHit", "Cache hit (%)", from, to, range.bucket(), samples,
                        DbMetricSample::getSampledAt,
                        DbMetricSample::getCacheHitPercent, MetricBucket::average),
                series("locks", "Lock waits", from, to, range.bucket(), samples,
                        DbMetricSample::getSampledAt,
                        sample -> number(sample.getLockWaits()), MetricBucket::max),
                // Capacity is a level sampled hourly, so the highest reading in a bucket is
                // the truest one; an average would smear a jump across the whole bucket.
                series("bytes", "Bytes", from, to, range.bucket(), samples,
                        DbMetricSample::getSampledAt,
                        sample -> number(sample.getBytes()), MetricBucket::max));

        Map<String, Double> current = new LinkedHashMap<>();
        current.put("latency", newest(samples, sample -> sample.isOk()
                ? number(sample.getLatencyMs()) : null));
        current.put("sessions", newest(samples, sample -> number(sample.getSessions())));
        current.put("qps", newest(samples, DbMetricSample::getQps));
        current.put("cacheHit", newest(samples, DbMetricSample::getCacheHitPercent));
        current.put("locks", newest(samples, sample -> number(sample.getLockWaits())));
        current.put("tableCount", newestKnown(samples, sample -> number(sample.getTableCount())));
        current.put("rowCount", newestKnown(samples, sample -> number(sample.getRowCount())));
        current.put("bytes", newestKnown(samples, sample -> number(sample.getBytes())));

        DbMetricSample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        return new DbMetricsView(last == null || last.isOk(), last == null ? null : last.getMessage(),
                last == null ? null : last.getSampledAt(), uptime(samples), series, current);
    }

    /**
     * One task's own curves. The shared process resources are deliberately absent: they belong
     * to the dashboard card (and to its admin-only feed), not to any one project.
     */
    public ProjectMetricsView project(long projectId, MetricsRange range) {
        Instant to = clock.instant();
        Instant from = to.minus(range.span());
        List<TaskMetricSample> samples = taskSamples
                .findByProjectIdAndSampledAtAfterOrderBySampledAtAsc(projectId, from);

        List<ChartSeries> series = new ArrayList<>();
        series.add(series("rows", "Rows synced", from, to, range.bucket(), samples,
                TaskMetricSample::getSampledAt,
                sample -> number(sample.getRowsDelta()), MetricBucket::sum));
        series.add(series("lag", "Lag (ms)", from, to, range.bucket(), samples,
                TaskMetricSample::getSampledAt,
                sample -> number(sample.getLagMs()), MetricBucket::average));
        series.add(series("failures", "Consecutive failures", from, to, range.bucket(), samples,
                TaskMetricSample::getSampledAt,
                sample -> number(sample.getConsecutiveFailures()), MetricBucket::max));

        Map<String, Double> current = new LinkedHashMap<>();
        current.put("rowsTotal", newest(samples, sample -> number(sample.getRowsTotal())));
        current.put("lag", newest(samples, sample -> number(sample.getLagMs())));
        current.put("failures", newest(samples, sample -> number(sample.getConsecutiveFailures())));

        List<MinuteChange> changes = changes(projectId, from, to);
        addChangeCurves(from, to, range, changes, series);

        TaskMetricSample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        return new ProjectMetricsView(last == null ? null : last.getSampledAt(),
                last == null ? null : last.getStatus(), series, current,
                changeShare(changes), hotObjects(projectId, from, to));
    }

    /** The shared process curves, for the one dashboard card that shows them. */
    public AppMetricsView app(MetricsRange range) {
        Instant to = clock.instant();
        Instant from = to.minus(range.span());
        List<AppMetricSample> apps = appSamples.findBySampledAtAfterOrderBySampledAtAsc(from);
        List<ChartSeries> series = new ArrayList<>();
        Map<String, Double> current = new LinkedHashMap<>();
        addProcess(apps, from, to, range, series, current);
        AppMetricSample last = apps.isEmpty() ? null : apps.get(apps.size() - 1);
        return new AppMetricsView(last == null ? null : last.getSampledAt(), series, current);
    }

    private void addProcess(List<AppMetricSample> apps, Instant from, Instant to, MetricsRange range,
                            List<ChartSeries> series, Map<String, Double> current) {
        series.add(series("cpu", "Process CPU (%)", from, to, range.bucket(), apps,
                AppMetricSample::getSampledAt, AppMetricSample::getCpuPercent,
                MetricBucket::average));
        series.add(series("heap", "Heap used (%)", from, to, range.bucket(), apps,
                AppMetricSample::getSampledAt, MetricsQueryService::heapPercent,
                MetricBucket::average));
        series.add(series("threads", "Threads", from, to, range.bucket(), apps,
                AppMetricSample::getSampledAt,
                sample -> number(sample.getThreads()), MetricBucket::average));
        series.add(series("gcTime", "GC time (ms)", from, to, range.bucket(), apps,
                AppMetricSample::getSampledAt,
                sample -> number(sample.getGcTimeMs()), MetricBucket::max));

        current.put("cpu", newest(apps, AppMetricSample::getCpuPercent));
        current.put("heap", newest(apps, MetricsQueryService::heapPercent));
        current.put("threads", newest(apps, sample -> number(sample.getThreads())));
        current.put("gcCount", newest(apps, sample -> number(sample.getGcCount())));
        current.put("gcTime", newest(apps, sample -> number(sample.getGcTimeMs())));
        current.put("sysLoad", newest(apps, AppMetricSample::getSysLoadPercent));
        current.put("sysMem", newest(apps, AppMetricSample::getSysMemPercent));
        current.put("disk", newest(apps, AppMetricSample::getDiskPercent));
    }

    private void addChangeCurves(Instant from, Instant to, MetricsRange range,
                                 List<MinuteChange> changes, List<ChartSeries> series) {
        for (ChangeType type : CHANGE_ORDER) {
            List<MinuteChange> ofType = changes.stream().filter(c -> c.type() == type).toList();
            series.add(series(type.name().toLowerCase(java.util.Locale.ROOT), type.name(),
                    from, to, range.bucket(),
                    ofType, MinuteChange::at, MinuteChange::count, MetricBucket::sum));
        }
    }

    private List<MinuteChange> changes(long projectId, Instant from, Instant to) {
        List<MinuteChange> changes = new ArrayList<>();
        for (Object[] row : changeLogs.minutelyChangesForProject(
                projectId, Timestamp.from(from), Timestamp.from(to))) {
            ChangeType type = parseType(row[1]);
            if (type != null) {
                changes.add(new MinuteChange(toInstant(row[0]), type, number((Number) row[2])));
            }
        }
        return changes;
    }

    private List<SeriesPoint> changeShare(List<MinuteChange> changes) {
        List<SeriesPoint> share = new ArrayList<>();
        for (ChangeType type : CHANGE_ORDER) {
            double total = 0;
            boolean any = false;
            for (MinuteChange change : changes) {
                if (change.type() == type) {
                    total += change.count();
                    any = true;
                }
            }
            if (any) {
                share.add(new SeriesPoint(type.name(), total));
            }
        }
        return share;
    }

    private List<SeriesPoint> hotObjects(long projectId, Instant from, Instant to) {
        List<SeriesPoint> hot = new ArrayList<>();
        for (Object[] row : changeLogs.topChangedObjects(projectId, Timestamp.from(from),
                Timestamp.from(to), PageRequest.of(0, HOT_OBJECT_LIMIT))) {
            hot.add(new SeriesPoint(String.valueOf(row[0]), number((Number) row[1])));
        }
        return hot;
    }

    private <T> ChartSeries series(String key, String label, Instant from, Instant to,
                                   Duration bucket, List<T> samples,
                                   Function<T, Instant> at, Function<T, Double> value,
                                   Function<List<Double>, Double> reduction) {
        List<List<T>> buckets = MetricBucket.split(from, to, bucket, samples, at);
        List<SeriesPoint> points = new ArrayList<>(buckets.size());
        Instant cursor = from;
        for (List<T> samplesInBucket : buckets) {
            List<Double> values = new ArrayList<>(samplesInBucket.size());
            for (T sample : samplesInBucket) {
                values.add(value.apply(sample));
            }
            points.add(new SeriesPoint(cursor.toString(), reduction.apply(values)));
            cursor = cursor.plus(bucket);
        }
        return new ChartSeries(key, label, points);
    }

    private Double uptime(List<DbMetricSample> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        long ok = samples.stream().filter(DbMetricSample::isOk).count();
        return ok * 100.0 / samples.size();
    }

    /** The newest row's figure, even when it is null: an unknown now must not show a stale number. */
    private <T> Double newest(List<T> samples, Function<T, Double> value) {
        return samples.isEmpty() ? null : value.apply(samples.get(samples.size() - 1));
    }

    /** Capacity is sampled hourly, so the newest row usually has none; the last real reading is the level. */
    private <T> Double newestKnown(List<T> samples, Function<T, Double> value) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            Double figure = value.apply(samples.get(i));
            if (figure != null) {
                return figure;
            }
        }
        return null;
    }

    private static Double heapPercent(AppMetricSample sample) {
        if (sample.getHeapUsed() == null || sample.getHeapMax() == null
                || sample.getHeapMax() <= 0) {
            return null;
        }
        return sample.getHeapUsed() * 100.0 / sample.getHeapMax();
    }

    private static Double number(Number value) {
        return value == null ? null : value.doubleValue();
    }

    private static ChangeType parseType(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ChangeType.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant toInstant(Object raw) {
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        return java.time.LocalDateTime.parse(String.valueOf(raw).replace(' ', 'T'))
                .toInstant(java.time.ZoneOffset.UTC);
    }

    private record MinuteChange(Instant at, ChangeType type, double count) {
    }
}
