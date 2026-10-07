package com.qqmu.jync.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Stats stats = new Stats();
        stats.setProjectCount(projectRepository.count());
        stats.setEnabledProjectCount(projectRepository.countByEnabledTrue());
        stats.setDatabaseCount(databaseConfigRepository.count());

        List<Project> projects = projectRepository.findAll();

        // Tracked-table counts for every project in ONE grouped query. Reused both for the
        // synced-tables total below and for the per-project overview rows, instead of issuing
        // one findByProjectIdAndObjectType per project (twice over).
        Map<Long, Long> trackedCountByProject = new HashMap<>();
        for (Object[] row : progressRepository.countByObjectTypeGroupedByProject(ObjectType.TABLE)) {
            trackedCountByProject.put((Long) row[0], (Long) row[1]);
        }

        // Count selected tables across projects. An empty selection means "all tables", which
        // cannot be counted without connecting, so those fall back to the number of tables
        // actually synced so far.
        long selectedTables = 0;
        for (Project project : projects) {
            SyncConfig config = contextFactory.parseConfig(project);
            if (!config.getTables().isEmpty()) {
                selectedTables += config.getTables().size();
            } else {
                selectedTables += trackedCountByProject.getOrDefault(project.getId(), 0L);
            }
        }
        stats.setSyncedTableCount(selectedTables);

        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        stats.setChangesToday(changeLogRepository.countByOccurredAtAfter(dayAgo));
        stats.setRowsToday(changeLogRepository.sumAffectedRowsSince(dayAgo));

        stats.setErrorTaskCount(taskStore.findByStatus(TaskStatus.ERROR).size());
        stats.setRecentChanges(changeLogRepository.findTop5ByOrderByOccurredAtDesc());

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

        public String statusName() {
            if (task == null || task.getStatus() == null) {
                return TaskStatus.STOPPED.name();
            }
            return task.getStatus().name();
        }
    }
}
