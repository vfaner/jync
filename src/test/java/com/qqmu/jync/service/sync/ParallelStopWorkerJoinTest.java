package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * The stop contract says no worker writes after the load call returns. Reaching the worker
 * join is the hard part: the stop is delivered by interrupting the coordinator, so its
 * interrupt flag is <em>already set</em> when the join starts — and
 * {@code awaitTermination} on an interrupted thread throws immediately instead of waiting.
 * The join was therefore skipped exactly in the case it exists for: the target kept gaining
 * one more chunk after "stop" returned (observed as a rare extra row in
 * {@code DataSyncServiceParallelLoadTest}).
 */
class ParallelStopWorkerJoinTest {

    @Test
    void theJoinWaitsForAWorkerEvenWhenTheCoordinatorIsAlreadyInterrupted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        pool.submit(() -> {
            running.countDown();
            // A JDBC call that ignores interruption: it swallows the interrupt and keeps
            // going, exactly like a driver in the middle of a network round trip.
            long until = System.currentTimeMillis() + 300;
            while (System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // Keep working, as the driver would.
                }
            }
            finished.set(true);
        });
        running.await();

        Thread.currentThread().interrupt();
        try {
            DataSyncService.stopWorkers(pool);

            assertThat(finished)
                    .as("the join must wait for the worker, not return the instant it starts")
                    .isTrue();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the stop stays visible to the caller after the join")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
