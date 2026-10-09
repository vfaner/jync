package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;

/**
 * P2: replacing a job must not leave the project unscheduled when the new registration fails —
 * the previous job/trigger are restored and the failure surfaces.
 */
class SyncSchedulerReplacementTest {

    private Scheduler quartz;
    private SyncTaskStore taskStore;
    private SyncContextFactory contextFactory;
    private SyncScheduler syncScheduler;

    private JobDetail oldJob;
    private Trigger oldTrigger;

    @BeforeEach
    void setUp() throws Exception {
        quartz = mock(Scheduler.class);
        taskStore = mock(SyncTaskStore.class);
        contextFactory = mock(SyncContextFactory.class);
        syncScheduler = new SyncScheduler(quartz, new SyncProperties(), taskStore,
                contextFactory);

        when(taskStore.ensureTask(any())).thenReturn(new SyncTask());
        when(contextFactory.parseConfig(any())).thenReturn(new SyncConfig());

        oldJob = JobBuilder.newJob(SyncJob.class)
                .withIdentity("sync-job-9", "jync")
                .usingJobData(SyncJob.PROJECT_ID, 9L)
                .storeDurably()
                .build();
        oldTrigger = TriggerBuilder.newTrigger()
                .withIdentity("sync-trigger-9", "jync")
                .startNow()
                .build();
        when(quartz.getJobDetail(any())).thenReturn(oldJob);
        when(quartz.getTrigger(any())).thenReturn(oldTrigger);
    }

    @Test
    void aFailedReplacementRestoresThePreviousRegistration() throws Exception {
        // The new scheduleJob fails (e.g. transient scheduler hiccup). The old registration
        // was already deleted, so it must be re-registered.
        doThrow(new SchedulerException("boom"))
                .doReturn(new java.util.Date())
                .when(quartz).scheduleJob(any(JobDetail.class), any(Trigger.class));

        Project project = new Project();
        project.setId(9L);
        project.setName("demo");
        project.setEnabled(true);

        assertThatThrownBy(() -> syncScheduler.schedule(project))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not schedule");

        ArgumentCaptor<JobDetail> jobs = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(quartz, times(2)).scheduleJob(jobs.capture(), triggers.capture());
        // Second call is the rollback with the exact captured prior objects.
        assertThat(jobs.getAllValues().get(1)).isSameAs(oldJob);
        assertThat(triggers.getAllValues().get(1)).isSameAs(oldTrigger);
    }

    @Test
    void aSuccessfulReplacementDoesNotRestoreAnything() throws Exception {
        Project project = new Project();
        project.setId(9L);
        project.setName("demo");
        project.setEnabled(true);

        syncScheduler.schedule(project);

        verify(quartz, times(1)).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }
}
