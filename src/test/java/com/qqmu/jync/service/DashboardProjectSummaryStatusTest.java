package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;

/**
 * The dashboard overview row must name the same status the project list and the detail
 * page show: the enabled flag is the live state, and a task-row ERROR hat wins over it.
 * Reading the raw task status instead reproduces the list page's old contradiction —
 * a clean CDC stop leaves the row RUNNING, so a stopped project keeps a green badge.
 */
class DashboardProjectSummaryStatusTest {

    private static DashboardService.ProjectSummary summary(boolean enabled, TaskStatus status) {
        Project project = new Project();
        project.setId(1L);
        project.setEnabled(enabled);
        SyncTask task = new SyncTask();
        task.setProjectId(1L);
        task.setStatus(status);
        DashboardService.ProjectSummary summary = new DashboardService.ProjectSummary();
        summary.setProject(project);
        summary.setTask(task);
        return summary;
    }

    @Test
    void aDisabledProjectWithAResidualRunningRowReadsStopped() {
        assertThat(summary(false, TaskStatus.RUNNING).statusName())
                .isEqualTo(TaskStatus.STOPPED.name());
    }

    @Test
    void anEnabledProjectWhoseCycleHasNotStartedYetReadsRunning() {
        assertThat(summary(true, TaskStatus.STOPPED).statusName())
                .isEqualTo(TaskStatus.RUNNING.name());
    }

    @Test
    void theErrorHatWinsOverEnabled() {
        assertThat(summary(true, TaskStatus.ERROR).statusName())
                .isEqualTo(TaskStatus.ERROR.name());
        assertThat(summary(false, TaskStatus.ERROR).statusName())
                .isEqualTo(TaskStatus.ERROR.name());
    }

    @Test
    void aMissingTaskRowDegradesToTheEnabledFlag() {
        Project project = new Project();
        project.setId(1L);
        project.setEnabled(true);
        DashboardService.ProjectSummary summary = new DashboardService.ProjectSummary();
        summary.setProject(project);
        assertThat(summary.statusName()).isEqualTo(TaskStatus.RUNNING.name());
    }
}
