package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.test.util.ReflectionTestUtils;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;

/**
 * A Quartz fire whose project was deleted must not run forever into a warn-only log.
 * The job self-deletes when the project row is gone; otherwise it routes through the
 * runner exactly as a live schedule would.
 */
class SyncJobOrphanTest {

    private SyncJob job;
    private SyncTaskRunner runner;
    private JobExecutionContext context;
    private Scheduler scheduler;
    private final JobKey jobKey = JobKey.jobKey("sync-job-1", "jync");

    @BeforeEach
    void setUp() {
        job = new SyncJob();
        runner = mock(SyncTaskRunner.class);
        ReflectionTestUtils.setField(job, "runner", runner);

        JobDataMap data = new JobDataMap();
        data.put(SyncJob.PROJECT_ID, 1L);
        JobDetail detail = mock(JobDetail.class);
        when(detail.getKey()).thenReturn(jobKey);
        scheduler = mock(Scheduler.class);
        context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getJobDetail()).thenReturn(detail);
        when(context.getScheduler()).thenReturn(scheduler);
    }

    @Test
    void aMissingProjectDeletesTheOrphanedJob() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.empty());

        assertThatCode(() -> job.execute(context)).doesNotThrowAnyException();

        verify(scheduler).deleteJob(jobKey);
        verify(runner).forget(1L);
        verify(runner, never()).runOnce(1L);
    }

    @Test
    void anExistingProjectRunsTheCycleAsUsual() {
        when(runner.findProject(1L)).thenReturn(Optional.of(project(true)));
        when(runner.runOnce(1L)).thenReturn(
                SyncTaskRunner.Outcome.executed(new SyncResult()));

        assertThatCode(() -> job.execute(context)).doesNotThrowAnyException();

        verify(runner).runOnce(1L);
    }

    /**
     * Multi-instance guard: a stop processed on another node leaves this node's trigger
     * alive. The fire must consult the durable enabled flag, drop the stale schedule and
     * run nothing — a stopped project may not keep syncing from one node's point of view.
     */
    @Test
    void aDisabledProjectDeletesTheStaleJobWithoutRunningACycle() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.of(project(false)));

        assertThatCode(() -> job.execute(context)).doesNotThrowAnyException();

        verify(scheduler).deleteJob(jobKey);
        verify(runner, never()).runOnce(1L);
    }

    private static Project project(boolean enabled) {
        Project project = new Project();
        project.setId(1L);
        project.setEnabled(enabled);
        return project;
    }
}
