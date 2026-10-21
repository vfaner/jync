package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import com.qqmu.jync.dto.SyncResult;
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
    }

    @Test
    void aQueuedRunLogsTheStartTheProjectNameAndTheElapsedTime() throws Exception {
        when(runner.projectExists(1L)).thenReturn(true);
        when(runner.projectEnabled(1L)).thenReturn(true);
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());

        List<String> info = runAndCaptureInfo("订单同步");

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("订单同步").contains("scheduled sync starting"));
        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("订单同步").contains("finished in").contains("queued"));
    }

    @Test
    void anExecutedCycleFinishesWithTheResultSummary() throws Exception {
        SyncResult result = mock(SyncResult.class);
        when(result.summary()).thenReturn("inserted 12, updated 3");
        when(runner.projectExists(1L)).thenReturn(true);
        when(runner.projectEnabled(1L)).thenReturn(true);
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.executed(result));

        List<String> info = runAndCaptureInfo("订单同步");

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("finished in").contains("inserted 12, updated 3"));
    }

    @Test
    void aFailingRunStillReportsThatItEnded() throws Exception {
        when(runner.projectExists(1L)).thenReturn(true);
        when(runner.projectEnabled(1L)).thenReturn(true);
        when(runner.runOnce(1L)).thenThrow(new RuntimeException("connection reset"));

        List<String> info = runAndCaptureInfo("订单同步");

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("finished in").contains("connection reset"));
    }

    @Test
    void aRunWithoutAStoredNameFallsBackToTheProjectId() throws Exception {
        when(runner.projectExists(1L)).thenReturn(true);
        when(runner.projectEnabled(1L)).thenReturn(true);
        when(runner.runOnce(1L)).thenReturn(SyncTaskRunner.Outcome.queued());
        JobExecutionContext context = context(null);

        List<String> info = capture(context);

        assertThat(info).anySatisfy(line -> assertThat(line)
                .contains("Project '1'").contains("scheduled sync starting"));
    }

    private List<String> runAndCaptureInfo(String projectName) throws Exception {
        return capture(context(projectName));
    }

    private List<String> capture(JobExecutionContext context) throws Exception {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncJob.class);
        try {
            job.execute(context);
        } finally {
            LogCapture.detach(SyncJob.class, logs);
        }
        return LogCapture.messagesAt(Level.INFO, logs);
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
