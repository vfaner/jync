package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.service.task.SyncTaskRunner;

@ExtendWith(MockitoExtension.class)
class TableReloadJobServiceTest {

    @Mock private TableMaintenanceService maintenance;
    @Mock private SyncTaskRunner taskRunner;

    private TableReloadJobService service;

    @BeforeEach
    void setUp() {
        service = new TableReloadJobService(maintenance, taskRunner);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void aCompletedReloadReportsItsRowsAndSummary() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(3);
        result.setRowsUpdated(1);
        SyncTaskRunner.Outcome outcome = outcome("executed", result);
        when(maintenance.reload(1L, "orders")).thenReturn(outcome);

        String id = service.submit(1L, "orders");
        TableReloadJobService.JobView job = awaitStatus(1L, id,
                TableReloadJobService.Status.DONE);

        assertThat(job.rowsInserted()).isEqualTo(3);
        assertThat(job.rowsUpdated()).isEqualTo(1);
        assertThat(job.summary()).isNotNull();
    }

    @Test
    void cancellingARunningReloadAsksTheCycleToStopAndReportsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SyncResult stopped = new SyncResult();
        stopped.setStopped(true);
        SyncTaskRunner.Outcome outcome = outcome("executed", stopped);
        when(maintenance.reload(1L, "orders")).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return outcome;
        });

        String id = service.submit(1L, "orders");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.cancel(1L, id)).isTrue();
        release.countDown();

        TableReloadJobService.JobView job = awaitStatus(1L, id,
                TableReloadJobService.Status.CANCELLED);
        assertThat(job.status()).isEqualTo(TableReloadJobService.Status.CANCELLED);
    }

    private TableReloadJobService.JobView awaitStatus(
            Long projectId, String id, TableReloadJobService.Status expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        TableReloadJobService.JobView last = null;
        while (System.nanoTime() < deadline) {
            last = service.find(projectId, id).orElseThrow();
            if (last.status() == expected) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("job did not reach " + expected + ": " + last);
    }

    private static SyncTaskRunner.Outcome outcome(String factory, SyncResult result) {
        return ReflectionTestUtils.invokeMethod(SyncTaskRunner.Outcome.class, factory, result);
    }
}
