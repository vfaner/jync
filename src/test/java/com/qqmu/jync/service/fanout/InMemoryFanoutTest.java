package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * The in-process transport is the first fan-out milestone: every target sees the same
 * ordered stream, and a full target queue applies backpressure instead of silently dropping
 * events. Per-target disk spill (so a slow target stops blocking the others) comes later.
 */
class InMemoryFanoutTest {

    @Test
    void oneSourceFeedsTwoTargetsInTheSameOrder() throws Exception {
        InMemoryEventTransport transport = new InMemoryEventTransport(16);
        RecordingApplier a = new RecordingApplier(2);
        RecordingApplier b = new RecordingApplier(2);
        FanoutTargetWorker workerA = new FanoutTargetWorker("a", transport.subscribe("target-a"), a);
        FanoutTargetWorker workerB = new FanoutTargetWorker("b", transport.subscribe("target-b"), b);
        workerA.start();
        workerB.start();
        try {
            publishTransaction(transport, "tx-1", "0/10", 1);
            publishTransaction(transport, "tx-2", "0/20", 2);

            assertThat(a.transactions.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(b.transactions.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(a.rows).containsExactly("tx-1:1", "tx-2:2");
            assertThat(b.rows).containsExactly("tx-1:1", "tx-2:2");
        } finally {
            workerA.close();
            workerB.close();
            transport.close();
        }
    }

    @Test
    void aFullTargetQueueBlocksTheCaptureInsteadOfDropping() throws Exception {
        InMemoryEventTransport transport = new InMemoryEventTransport(1);
        EventTransport.Subscription sub = transport.subscribe("slow");
        transport.publish(ChangeEvent.commit("0/1", "tx-1", 1L));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> second = pool.submit(() -> {
                transport.publish(ChangeEvent.commit("0/2", "tx-2", 2L));
                return null;
            });
            Thread.sleep(200L);
            assertThat(second.isDone()).as("capture must wait for the slow target").isFalse();

            assertThat(sub.poll(Duration.ofSeconds(1))).isNotNull();
            second.get(2, TimeUnit.SECONDS);
            assertThat(second.isDone()).isTrue();
        } finally {
            pool.shutdownNow();
            transport.close();
        }
    }

    @Test
    void ddlIsDeliveredToEveryTarget() throws Exception {
        InMemoryEventTransport transport = new InMemoryEventTransport(16);
        RecordingApplier a = new RecordingApplier(0);
        RecordingApplier b = new RecordingApplier(0);
        FanoutTargetWorker workerA = new FanoutTargetWorker("a", transport.subscribe("a"), a);
        FanoutTargetWorker workerB = new FanoutTargetWorker("b", transport.subscribe("b"), b);
        workerA.start();
        workerB.start();
        try {
            transport.publish(ChangeEvent.ddl("0/30", "app", "orders",
                    "CREATE TABLE app.orders (id BIGINT PRIMARY KEY)", 3L));
            long deadline = System.currentTimeMillis() + 3_000L;
            while ((a.ddl.isEmpty() || b.ddl.isEmpty()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(a.ddl).containsExactly("orders");
            assertThat(b.ddl).containsExactly("orders");
        } finally {
            workerA.close();
            workerB.close();
            transport.close();
        }
    }

    private static void publishTransaction(InMemoryEventTransport transport, String tx,
                                           String commitPosition, long id) throws Exception {
        transport.publish(ChangeEvent.row("0/1" + id, tx, "app", "orders",
                ChangeEvent.ChangeOperation.INSERT, Map.of("id", id), null, 1L));
        transport.publish(ChangeEvent.commit(commitPosition, tx, 2L));
    }

    private static final class RecordingApplier implements EventApplier {
        final CountDownLatch transactions;
        final List<String> rows = new ArrayList<>();
        final List<String> ddl = new ArrayList<>();

        RecordingApplier(int expectedTransactions) {
            this.transactions = new CountDownLatch(expectedTransactions);
        }

        @Override
        public void applyTransaction(String transactionId, String commitPosition,
                                     List<ChangeEvent> events) {
            for (ChangeEvent event : events) {
                rows.add(transactionId + ":" + event.row().get("id"));
            }
            transactions.countDown();
        }

        @Override
        public void applyDdl(ChangeEvent event) {
            ddl.add(event.table());
        }
    }
}
