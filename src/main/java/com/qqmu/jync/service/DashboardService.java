package com.qqmu.jync.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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

        // Count selected tables across projects. An empty selection means "all tables", which
        // cannot be counted without connecting, so those fall back to the number of tables
        // actually synced so far.
        long selectedTables = 0;
        for (Project project : projects) {
            SyncConfig config = contextFactory.parseConfig(project);
            if (!config.getTables().isEmpty()) {
                selectedTables += config.getTables().size();
            } else {
                selectedTables += progressRepository
                        .findByProjectIdAndObjectType(project.getId(), ObjectType.TABLE).size();
            }
        }
        stats.setSyncedTableCount(selectedTables);

        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        stats.setChangesToday(changeLogRepository.countByOccurredAtAfter(dayAgo));
        stats.setRowsToday(changeLogRepository.sumAffectedRowsSince(dayAgo));

        stats.setErrorTaskCount(taskStore.findByStatus(TaskStatus.ERROR).size());
        stats.setRecentChanges(changeLogRepository.findTop5ByOrderByOccurredAtDesc());

        // Per-project rows for the overview table.
        List<ProjectSummary> summaries = new ArrayList<>();
        for (Project project : projects) {
            ProjectSummary summary = new ProjectSummary();
            summary.setProject(project);
            summary.setTask(taskStore.find(project.getId()).orElse(null));
            summary.setTrackedTableCount(progressRepository
                    .findByProjectIdAndObjectType(project.getId(), ObjectType.TABLE).size());
            summary.setTotalRowsSynced(changeLogRepository
                    .sumAffectedRowsByProject(project.getId()));
            summaries.add(summary);
        }
        // Same rule as the activity feed: five rows keep the card a fixed height no matter
        // how many projects exist; the card header links to the paged full list.
        stats.setProjectSummaries(summaries.size() > OVERVIEW_ROWS
                ? new ArrayList<>(summaries.subList(0, OVERVIEW_ROWS))
                : summaries);
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
