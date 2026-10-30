package com.qqmu.jync.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.Getter;
import lombok.Setter;

/** Computes the dashboard overview by querying current state; nothing is precomputed. */
@Service
public class DashboardService {

    /** Row ceiling for the two dashboard lists, so growing data never stretches the page. */
    private static final int OVERVIEW_ROWS = 5;

    /** Hours the trend chart covers, including the one in progress. */
    static final int SERIES_HOURS = 24;

    private static final DateTimeFormatter HOUR_LABEL =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final SyncProgressRepository progressRepository;
    private final ChangeLogRepository changeLogRepository;
    private final SyncContextFactory contextFactory;
    private final SyncTaskStore taskStore;

    public DashboardService(ProjectRepository projectRepository,
                            DatabaseConfigRepository databaseConfigRepository,
                            SyncProgressRepository progressRepository,
                            ChangeLogRepository changeLogRepository,
                            SyncContextFactory contextFactory,
                            SyncTaskStore taskStore) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.progressRepository = progressRepository;
        this.changeLogRepository = changeLogRepository;
        this.contextFactory = contextFactory;
        this.taskStore = taskStore;
    }

    @Transactional(readOnly = true)
    public Stats collect() {
        return collect(Instant.now());
    }

    Stats collect(Instant now) {
        Stats stats = new Stats();
        stats.setProjectCount(projectRepository.count());
        stats.setEnabledProjectCount(projectRepository.countByEnabledTrue());
        stats.setDatabaseCount(databaseConfigRepository.count());

        // Ordered by id: both the selected-table counting and the first-five overview
        // rows below depend on the order, and findAll() without a Sort is unspecified.
        List<Project> projects = projectRepository.findAll(Sort.by("id"));

        // Tracked-table counts for every project in ONE grouped query. Reused both for the
        // synced-tables total below and for the per-project overview rows, instead of issuing
        // one findByProjectIdAndObjectType per project (twice over).
        Map<Long, Long> trackedCountByProject = new HashMap<>();
        for (Object[] row : progressRepository.countByObjectTypeGroupedByProject(ObjectType.TABLE)) {
            trackedCountByProject.put((Long) row[0], (Long) row[1]);
        }

        // Count selected tables across projects. A LEGACY empty selection means "all tables",
        // which cannot be counted without connecting, so those fall back to the number of
        // tables actually synced so far. An explicitly saved empty selection means none — it
        // must count as zero, not inherit the progress rows of an earlier configuration.
        long selectedTables = 0;
        for (Project project : projects) {
            SyncConfig config = contextFactory.parseConfig(project);
            if (!config.getTables().isEmpty()) {
                selectedTables += config.getTables().size();
            } else if (!config.isObjectSelectionExplicit()) {
                selectedTables += trackedCountByProject.getOrDefault(project.getId(), 0L);
            }
        }
        stats.setSyncedTableCount(selectedTables);

        // The tile and the trend chart both say "the last 24 hours", so they must count the same
        // window: the chart's 24 hourly buckets, the last of which is the hour in progress.
        Instant windowStart = seriesStart(now);
        stats.setChangesToday(changeLogRepository.countByOccurredAtBetween(windowStart, now));
        stats.setRowsToday(changeLogRepository.sumAffectedRowsBetween(windowStart, now));

        stats.setErrorTaskCount(taskStore.findByStatus(TaskStatus.ERROR).size());
        stats.setRecentChanges(recentChanges());

        // Only the rows actually shown (the first five) need per-project data, so slice first
        // and batch-fetch their tasks and row totals — the old code built every project's row
        // then threw the rest away, one query per project each.
        List<Project> overviewProjects = projects.size() > OVERVIEW_ROWS
                ? new ArrayList<>(projects.subList(0, OVERVIEW_ROWS)) : projects;

        List<Long> overviewIds = new ArrayList<>(overviewProjects.size());
        for (Project project : overviewProjects) {
            overviewIds.add(project.getId());
        }
        Map<Long, SyncTask> taskByProject = taskStore.findByProjectIds(overviewIds);

        Map<Long, Long> rowsByProject = new HashMap<>();
        for (Object[] row : changeLogRepository.sumAffectedRowsGroupedByProject()) {
            rowsByProject.put((Long) row[0], (Long) row[1]);
        }

        List<ProjectSummary> summaries = new ArrayList<>(overviewProjects.size());
        for (Project project : overviewProjects) {
            ProjectSummary summary = new ProjectSummary();
            summary.setProject(project);
            summary.setTask(taskByProject.get(project.getId()));
            summary.setTrackedTableCount(
                    trackedCountByProject.getOrDefault(project.getId(), 0L).intValue());
            summary.setTotalRowsSynced(rowsByProject.getOrDefault(project.getId(), 0L));
            summaries.add(summary);
        }
        // Same rule as the activity feed: five rows keep the card a fixed height no matter
        // how many projects exist; the card header links to the paged full list.
        stats.setProjectSummaries(summaries);
        return stats;
    }

    /**
     * The few newest entries, for the dashboard feed. Split out of {@link #collect()} so the
     * feed's 10s poll costs one indexed query instead of recomputing every tile.
     */
    @Transactional(readOnly = true)
    public List<ChangeLog> recentChanges() {
        return changeLogRepository.findTop10ByOrderByOccurredAtDesc();
    }

    /**
     * The trend chart's 24 hourly buckets, oldest first, ending with the hour in progress.
     *
     * <p>Quiet hours are zeros rather than gaps: dropping them would let the newest point sit
     * days back while the axis still claimed "the last 24 hours".
     */
    @Transactional(readOnly = true)
    public ActivitySeries activitySeries() {
        return activitySeries(Instant.now());
    }

    /** The first hour of the window both the trend chart and the "Changes (24h)" tile cover. */
    static Instant seriesStart(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS).minus(SERIES_HOURS - 1, ChronoUnit.HOURS);
    }

    ActivitySeries activitySeries(Instant now) {
        Instant start = seriesStart(now);

        Map<Instant, long[]> byHour = new HashMap<>();
        for (Object[] row : changeLogRepository.hourlySeriesBetween(
                new java.sql.Timestamp(start.toEpochMilli()),
                new java.sql.Timestamp(now.toEpochMilli()))) {
            Instant hour = hourOf(row[0]);
            if (hour != null) {
                byHour.put(hour, new long[] {
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
            }
        }

        ZoneId zone = ZoneId.systemDefault();
        List<SeriesPoint> points = new ArrayList<>(SERIES_HOURS);
        long totalChanges = 0;
        long totalRows = 0;
        for (int i = 0; i < SERIES_HOURS; i++) {
            Instant hour = start.plus(i, ChronoUnit.HOURS);
            long[] bucket = byHour.get(hour);
            long changes = bucket == null ? 0 : bucket[0];
            long rows = bucket == null ? 0 : bucket[1];
            points.add(new SeriesPoint(HOUR_LABEL.format(hour.atZone(zone)), changes, rows));
            totalChanges += changes;
            totalRows += rows;
        }
        return new ActivitySeries(points, totalChanges, totalRows);
    }

    /** The hour boundary as an instant, whichever temporal type the driver handed back. */
    private static Instant hourOf(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime local) {
            // The session time zone is UTC (application.yml), so the wall clock is UTC.
            return local.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        return null;
    }

    /** One hour of the trend chart, labelled in the server's zone. */
    public record SeriesPoint(String label, long changes, long rows) {
    }

    /** The whole trend window plus its totals, for the chart header. */
    public record ActivitySeries(List<SeriesPoint> points, long totalChanges, long totalRows) {
    }

    /** Dashboard figures. */
    @Getter
    @Setter
    public static class Stats {
        private long projectCount;
        private long enabledProjectCount;
        private long databaseCount;
        private long syncedTableCount;
        private long changesToday;
        private long rowsToday;
        private long errorTaskCount;
        private List<ChangeLog> recentChanges = new ArrayList<>();
        private List<ProjectSummary> projectSummaries = new ArrayList<>();
    }

    /** One project's row in the dashboard overview. */
    @Getter
    @Setter
    public static class ProjectSummary {
        private Project project;
        private SyncTask task;
        private int trackedTableCount;
        private long totalRowsSynced;

        /** Same rule as the list and detail badges (see the projects.html badge comment):
         *  the ERROR hat wins, otherwise the enabled flag is the live state. The raw task
         *  status reads RUNNING for a cleanly stopped CDC project. */
        public String statusName() {
            if (task != null && task.getStatus() == TaskStatus.ERROR) {
                return TaskStatus.ERROR.name();
            }
            if (project != null && Boolean.TRUE.equals(project.getEnabled())) {
                return TaskStatus.RUNNING.name();
            }
            return TaskStatus.STOPPED.name();
        }
    }
}
