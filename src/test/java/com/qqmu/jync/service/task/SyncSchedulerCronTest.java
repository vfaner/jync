package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Properties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.service.cdc.LogCapture;

/**
 * A custom cron is only useful if it actually fires. This drives the real Quartz scheduler
 * (RAMJobStore, the production job class) instead of a mocked {@code Scheduler}, so a cron
 * that parses but never acquires a trigger — the reported "到时间不生效" — fails here.
 *
 * <p>The schedule log is part of the contract too: "it did not run at the time I set" cannot
 * be diagnosed when the log never says when it is supposed to run.
 */
class SyncSchedulerCronTest {

    private Scheduler quartz;
    private SyncContextFactory contextFactory;
    private SyncTaskRunner runner;
    private SyncScheduler syncScheduler;
    private SyncConfig config;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.put("org.quartz.scheduler.instanceName", "cron-test-" + System.nanoTime());
        props.put("org.quartz.threadPool.threadCount", "2");
        props.put("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        // Production default is 60s; the misfire test must reach that state in milliseconds.
        props.put("org.quartz.jobStore.misfireThreshold", "500");
        quartz = new StdSchedulerFactory(props).getScheduler();

        runner = mock(SyncTaskRunner.class);
        when(runner.projectExists(1L)).thenReturn(true);
        when(runner.projectEnabled(1L)).thenReturn(true);
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());
        quartz.setJobFactory((bundle, scheduler) -> {
            SyncJob job = new SyncJob();
            injectRunner(job, runner);
            return job;
        });

        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        when(taskStore.ensureTask(any())).thenReturn(new SyncTask());
        contextFactory = mock(SyncContextFactory.class);
        config = new SyncConfig();
        when(contextFactory.parseConfig(any())).thenReturn(config);
        syncScheduler = new SyncScheduler(quartz, new SyncProperties(), taskStore, contextFactory);
    }

    @AfterEach
    void tearDown() throws Exception {
        quartz.shutdown(true);
    }

    @Test
    void aCronProducesTheNextFireTimeTheUserAskedFor() throws Exception {
        config.setCronExpression("0 30 2 * * ?");

        syncScheduler.schedule(project("订单同步"));

        LocalDateTime next = nextFire();
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isEqualTo(30);
        assertThat(next.getSecond()).isZero();
    }

    @Test
    void aCronTriggerActuallyFiresTheJob() throws Exception {
        config.setCronExpression("0/1 * * * * ?");

        syncScheduler.schedule(project("订单同步"));
        quartz.start();

        // A registered trigger with a next fire time but no acquisition is exactly the
        // reported symptom; only a real scheduler proves the registration is live.
        verify(runner, timeout(5000).atLeastOnce()).runOnce(1L);
    }

    @Test
    void aCronWhoseFireTimeWasMissedWhileTheAppWasBusyStillRuns() throws Exception {
        // The reported symptom behind "cron 到时间不生效": the fire time passes while the
        // scheduler is paused, all its threads are busy with long cycles, or the machine is
        // under load. Discarding the missed fire then means a once-a-day cron silently skips
        // a whole day; for a poll interval discarding is right, for a cron it is not.
        LocalDateTime fireAt = LocalDateTime.now().plusSeconds(3).withNano(0);
        config.setCronExpression(String.format("%d %d %d * * ?", fireAt.getSecond(),
                fireAt.getMinute(), fireAt.getHour()));
        syncScheduler.schedule(project("订单同步"));
        quartz.start();
        quartz.pauseAll(); // stands in for a pool with no free worker at the fire time

        Thread.sleep(5_000); // past fireAt plus the 500ms misfire threshold in setUp
        quartz.resumeAll();

        verify(runner, timeout(5_000).atLeastOnce()).runOnce(1L);
    }

    @Test
    void theScheduleLogNamesTheNextFireTimeAndZone() throws Exception {
        config.setCronExpression("0 30 2 * * ?");
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncScheduler.class);
        try {
            syncScheduler.schedule(project("订单同步"));
        } finally {
            LogCapture.detach(SyncScheduler.class, logs);
        }

        String line = LogCapture.messagesAt(Level.INFO, logs).stream()
                .filter(l -> l.contains("Scheduled sync"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scheduling log: " + logs.list));
        assertThat(line)
                .as("operators must be able to check when the next run is due: %s", line)
                .contains("订单同步")
                .contains("cron: 0 30 2 * * ?")
                .contains("next fire")
                .contains(ZoneId.systemDefault().getId());
    }

    @Test
    void theJobCarriesTheProjectNameSoTheRunLogCanNameIt() throws Exception {
        syncScheduler.schedule(project("订单同步"));

        assertThat(quartz.getJobDetail(JobKey.jobKey("sync-job-1", "jync"))
                .getJobDataMap().getString(SyncJob.PROJECT_NAME))
                .isEqualTo("订单同步");
    }

    @Test
    void theNextFireTimeIsExposedSoThePageCanShowWhenItWillRun() throws Exception {
        config.setCronExpression("0 30 2 * * ?");
        syncScheduler.schedule(project("订单同步"));

        java.time.LocalDateTime next = syncScheduler.nextFireTime(1L);

        assertThat(next).isNotNull();
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isEqualTo(30);
    }

    @Test
    void anUnscheduledProjectHasNoNextFireTime() {
        assertThat(syncScheduler.nextFireTime(42L)).isNull();
    }

    @Test
    void anIntervalStillSchedulesWhenNoCronIsSet() throws Exception {
        config.setPollIntervalMs(30_000L);

        syncScheduler.schedule(project("订单同步"));

        Trigger trigger = quartz.getTrigger(triggerKey());
        assertThat(trigger).isNotNull();
        assertThat(trigger.getNextFireTime()).isNotNull();
    }

    private Project project(String name) {
        Project project = new Project();
        project.setId(1L);
        project.setName(name);
        project.setEnabled(true);
        return project;
    }

    private TriggerKey triggerKey() {
        return TriggerKey.triggerKey("sync-trigger-1", "jync");
    }

    private LocalDateTime nextFire() throws Exception {
        Trigger trigger = quartz.getTrigger(triggerKey());
        assertThat(trigger).as("the trigger must be registered").isNotNull();
        assertThat(trigger.getNextFireTime()).as("a trigger without a next fire time never runs")
                .isNotNull();
        return LocalDateTime.ofInstant(trigger.getNextFireTime().toInstant(),
                ZoneId.systemDefault());
    }

    private static void injectRunner(SyncJob job, SyncTaskRunner runner) {
        try {
            Field field = SyncJob.class.getDeclaredField("runner");
            field.setAccessible(true);
            field.set(job, runner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
