package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.LogCapture;

/**
 * What a scheduled run says at INFO: the project it belongs to, that it started, and how
 * long it took. Without these two lines a user whose cron "did not run" cannot tell whether
 * the schedule never fired or the cycle fired and did nothing.
 */
class SyncJobLogTest {

    private SyncTaskRunner runner;
    private SyncJob job;

    @BeforeEach
    void setUp() throws Exception {
        runner = mock(SyncTaskRunner.class);
        job = new SyncJob();
        Field field = SyncJob.class.getDeclaredField("runner");
        field.setAccessible(true);
        field.set(job, runner);
        SyncJob.resetIdleHeartbeatForTests();
        logger().setLevel(Level.DEBUG);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(SyncJob.class);
    }

    @Test
    void anIdleRunKeepsOneInfoHeartbeatWithoutAPerCycleStartLine() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.of(enabledProject()));
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());

        ListAppender<ILoggingEvent> logs = captureAll(context("订单同步"));

        List<String> info = LogCapture.messagesAt(Level.INFO, logs);
        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("订单同步").contains("finished in").contains("queued"));
        assertThat(info).noneSatisfy(line -> assertThat(line)
                .contains("scheduled sync starting"));
        assertThat(LogCapture.messagesAt(Level.DEBUG, logs)).anySatisfy(line -> assertThat(line)
                .contains("订单同步").contains("scheduled sync starting"));
    }

    @Test
    void repeatedIdleCyclesCollapseToOneHeartbeat() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.of(enabledProject()));
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());
        JobExecutionContext context = context("订单同步");

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncJob.class);
        try {
            job.execute(context);
            job.execute(context);
        } finally {
            LogCapture.detach(SyncJob.class, logs);
        }

        List<String> info = LogCapture.messagesAt(Level.INFO, logs);
        assertThat(info).filteredOn(line -> line.contains("finished in")).hasSize(1);
        assertThat(LogCapture.messagesAt(Level.DEBUG, logs))
                .filteredOn(line -> line.contains("finished in")).hasSize(1);
    }

    @Test
    void aChangedCycleIsStillInfoRightAfterAnIdleHeartbeat() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.of(enabledProject()));
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());
        JobExecutionContext context = context("订单同步");
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncJob.class);
        try {
            job.execute(context);                   // consumes the idle heartbeat

            SyncResult result = mock(SyncResult.class);
            when(result.hasChanges()).thenReturn(true);
            when(result.summary()).thenReturn("inserted 1");
            when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.executed(result));
            job.execute(context);
        } finally {
            LogCapture.detach(SyncJob.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.INFO, logs)).anySatisfy(line -> assertThat(line)
                .contains("finished in").contains("inserted 1"));
    }

    @Test
    void anExecutedCycleFinishesWithTheResultSummary() throws Exception {
        SyncResult result = mock(SyncResult.class);
        when(result.hasChanges()).thenReturn(true);
        when(result.summary()).thenReturn("inserted 12, updated 3");
        when(runner.findProject(1L)).thenReturn(Optional.of(enabledProject()));
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.executed(result));

        List<String> info = runAndCaptureInfo("订单同步");

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("finished in").contains("inserted 12, updated 3"));
    }

    @Test
    void aFailingRunStillReportsThatItEnded() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.of(enabledProject()));
        when(runner.runOnce(1L)).thenThrow(new RuntimeException("connection reset"));

        List<String> info = runAndCaptureInfo("订单同步");

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("finished in").contains("connection reset"));
    }

    @Test
    void aRunWithoutAStoredNameFallsBackToTheProjectId() throws Exception {
        when(runner.findProject(1L)).thenReturn(Optional.of(enabledProject()));
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());
        JobExecutionContext context = context(null);

        List<String> info = LogCapture.messagesAt(Level.INFO, captureAll(context));

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("Project '1'").contains("finished in"));
    }

    private static Project enabledProject() {
        Project project = new Project();
        project.setId(1L);
        project.setEnabled(true);
        return project;
    }

    private List<String> runAndCaptureInfo(String projectName) throws Exception {
        return capture(context(projectName));
    }

    private List<String> capture(JobExecutionContext context) throws Exception {
        return LogCapture.messagesAt(Level.INFO, captureAll(context));
    }

    private ListAppender<ILoggingEvent> captureAll(JobExecutionContext context) throws Exception {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncJob.class);
        try {
            job.execute(context);
        } finally {
            LogCapture.detach(SyncJob.class, logs);
        }
        return logs;
    }

    private static JobExecutionContext context(String projectName) {
        JobDataMap data = new JobDataMap();
        data.put(SyncJob.PROJECT_ID, 1L);
        if (projectName != null) {
            data.put(SyncJob.PROJECT_NAME, projectName);
        }
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(data);
        return context;
    }
}
