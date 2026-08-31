package com.synctool.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.dto.SyncConfig;
import com.synctool.model.ChangeLog;
import com.synctool.model.ObjectType;
import com.synctool.model.Project;
import com.synctool.model.SyncTask;
import com.synctool.model.TaskStatus;
import com.synctool.repository.ChangeLogRepository;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.repository.SyncProgressRepository;
import com.synctool.service.task.SyncContextFactory;
import com.synctool.service.task.SyncTaskStore;

import lombok.Getter;
import lombok.Setter;

/** Computes the dashboard overview by querying current state; nothing is precomputed. */
@Service
public class DashboardService {

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
        stats.setRunningTaskCount(taskStore.findByStatus(TaskStatus.RUNNING).size());
        stats.setRecentChanges(changeLogRepository.findTop10ByOrderByOccurredAtDesc());

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
            summary.setFailureCount(changeLogRepository
                    .countByProjectIdAndSuccessFalse(project.getId()));
            summaries.add(summary);
        }
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
        private long runningTaskCount;
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
        private long failureCount;

        public String statusName() {
            if (task == null || task.getStatus() == null) {
                return TaskStatus.STOPPED.name();
            }
            return task.getStatus().name();
        }
    }
}
