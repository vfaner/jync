package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class SpoolingEventTransportTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void eachTargetReadsTheWholeStreamAndResumesFromItsAcknowledgedOffset(@TempDir Path dir)
            throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription a = transport.subscribe("a");
        EventTransport.Subscription b = transport.subscribe("b");
        transport.publish(row("tx-1", 1L));
        transport.publish(row("tx-2", 2L));

        ChangeEvent first = a.poll(Duration.ofSeconds(1));
        assertThat(first.row()).containsEntry("id", 1L);
        a.ack(first);
        assertThat(b.poll(Duration.ofSeconds(1))).isNotNull();
        assertThat(b.poll(Duration.ofSeconds(1))).isNotNull();
        transport.close();

        SpoolingEventTransport reopened = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription a2 = reopened.subscribe("a");
        ChangeEvent next = a2.poll(Duration.ofSeconds(1));
        assertThat(next.transactionId()).isEqualTo("tx-2");
        reopened.close();
    }

    @Test
    void rewindingReplaysAnEventThatWasPolledButNotAcked(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription sub = transport.subscribe("rewind");
        transport.publish(row("tx-1", 1L));

        ChangeEvent first = sub.poll(Duration.ofSeconds(1));
        assertThat(first.transactionId()).isEqualTo("tx-1");
        // A worker died after polling but before applying/acking; the replacement rewinds.
        sub.rewindToAck();

        ChangeEvent replayed = sub.poll(Duration.ofSeconds(1));
        assertThat(replayed.transactionId()).isEqualTo("tx-1");
        transport.close();
    }

    @Test
    void ackingWhenCaughtUpTruncatesTheSpool(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription sub = transport.subscribe("caught-up");
        transport.publish(row("tx-1", 1L));

        ChangeEvent event = sub.poll(Duration.ofSeconds(1));
        sub.ack(event);

        assertThat(java.nio.file.Files.size(dir.resolve("caught-up.log"))).isZero();
        transport.close();
    }

    @Test
    void anIdlePollParksInsteadOfSpinning(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription sub = transport.subscribe("idle");

        long started = System.nanoTime();
        assertThat(sub.poll(Duration.ofMillis(120))).isNull();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        // Without the wait every worker looped seek+readInt at EOF and burned a core.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(90L);
        transport.close();
    }

    @Test
    void aCappedTargetsDroppedWindowIsMarkedForRebaseline(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 64, mapper);
        transport.subscribe("capped");
        transport.publish(row("tx-1", 1L));

        assertThat(transport.needsRebaseline()).isTrue();
        transport.clearRebaselineMarks();
        assertThat(transport.needsRebaseline()).isFalse();
        transport.close();
        // The marker is on disk, so a restart (a fresh transport) still sees it.
    }

    @Test
    void aCrashMidRecordDoesNotBrickTheTarget(@TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription sub = transport.subscribe("torn");
        transport.publish(row("tx-1", 1L));
        transport.close();

        // The writer died after the 4-byte length but before the payload.
        try (java.io.RandomAccessFile tail = new java.io.RandomAccessFile(
                dir.resolve("torn.log").toFile(), "rw")) {
            tail.seek(tail.length());
            tail.writeInt(64);
        }

        SpoolingEventTransport reopened = new SpoolingEventTransport(dir, 1_000_000, mapper);
        EventTransport.Subscription resumed = reopened.subscribe("torn");

        ChangeEvent first = resumed.poll(Duration.ofSeconds(1));
        assertThat(first).isNotNull();
        assertThat(first.transactionId()).isEqualTo("tx-1");
        // The torn tail was cut, so the next poll is empty instead of failing forever.
        assertThat(resumed.poll(Duration.ofMillis(50))).isNull();
        reopened.close();
    }

    @Test
    void exceedingThePerTargetCapFailsThatTargetVisiblyWithoutAbortingTheCapture(
            @TempDir Path dir) throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 64, mapper);
        EventTransport.Subscription slow = transport.subscribe("slow");

        // The capture keeps running: a dead target must not stop the healthy ones.
        transport.publish(row("tx-1", 1L));
        transport.publish(row("tx-2", 2L));

        assertThatThrownBy(() -> slow.poll(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer writable");
        transport.close();
    }

    private static ChangeEvent row(String tx, long id) {
        return ChangeEvent.row("0/" + id, tx, "app", "orders",
                ChangeEvent.ChangeOperation.UPSERT, Map.of("id", id), null, 1L);
    }
}
