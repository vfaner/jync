package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/**
 * A target worker that dies after polling but before committing must replay that transaction
 * from the spool when its replacement starts: the ack offset is the only durable checkpoint.
 */
class FanoutTargetWorkerRestartTest {

    @Test
    void anOrphanRowTailIsDiscardedInsteadOfWedgingTheWorker(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000,
                JsonMapper.builder().build());
        EventTransport.Subscription sub = transport.subscribe("t2");
        // The capture died after appending tx-1's row but before its COMMIT. A restart then
        // publishes tx-2; the incomplete tx-1 must be dropped, not fail the worker forever.
        transport.publish(ChangeEvent.row("binlog.000001:10", "tx-1", "app", "orders",
                ChangeEvent.ChangeOperation.UPSERT, Map.of("id", 1), null, 1L));
        transport.publish(ChangeEvent.row("binlog.000001:30", "tx-2", "app", "orders",
                ChangeEvent.ChangeOperation.UPSERT, Map.of("id", 2), null, 3L));
        transport.publish(ChangeEvent.commit("binlog.000001:40", "tx-2", 4L));

        List<String> applied = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        FanoutTargetWorker worker = new FanoutTargetWorker("t2", sub,
                new EventApplier() {
                    @Override
                    public void applyTransaction(String transactionId, String commitPosition,
                                                 List<ChangeEvent> rows) {
                        applied.add(transactionId + ":" + rows.size());
                        done.countDown();
                    }

                    @Override
                    public void applyDdl(ChangeEvent event) {
                    }
                });
        worker.start();
        try {
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(applied).containsExactly("tx-2:1");
            assertThat(worker.isAlive()).isTrue();
        } finally {
            worker.close();
            transport.close();
        }
    }

    @Test
    void aReplacementWorkerReplaysTheUncommittedTransaction(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000,
                JsonMapper.builder().build());
        EventTransport.Subscription sub = transport.subscribe("t1");
        transport.publish(ChangeEvent.row("binlog.000001:10", "tx-1", "app", "orders",
                ChangeEvent.ChangeOperation.UPSERT, Map.of("id", 1), null, 1L));
        transport.publish(ChangeEvent.commit("binlog.000001:20", "tx-1", 2L));

        FanoutTargetWorker failing = new FanoutTargetWorker("t1", sub,
                new EventApplier() {
                    @Override
                    public void applyTransaction(String transactionId, String commitPosition,
                                                 List<ChangeEvent> rows) {
                        throw new IllegalStateException("target down");
                    }

                    @Override
                    public void applyDdl(ChangeEvent event) {
                    }
                });
        failing.start();
        long deadline = System.currentTimeMillis() + 3_000L;
        while (failing.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(failing.isAlive()).isFalse();
        assertThat(failing.getLastError()).isNotNull();

        // The supervisor keeps the subscription and rewinds it to the last ack before starting
        // the replacement; the failed transaction was never acked.
        sub.rewindToAck();
        List<String> applied = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        FanoutTargetWorker replacement = new FanoutTargetWorker("t1", sub,
                new EventApplier() {
                    @Override
                    public void applyTransaction(String transactionId, String commitPosition,
                                                 List<ChangeEvent> rows) {
                        applied.add(transactionId);
                        done.countDown();
                    }

                    @Override
                    public void applyDdl(ChangeEvent event) {
                    }
                });
        replacement.start();
        try {
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(applied).containsExactly("tx-1");
        } finally {
            replacement.close();
            transport.close();
        }
    }
}
