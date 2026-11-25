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
        assertThat(first.row()).containsEntry("id", 1);
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
    void exceedingThePerTargetCapIsAVisibleErrorNotASilentDrop(@TempDir Path dir)
            throws Exception {
        SpoolingEventTransport transport = new SpoolingEventTransport(dir, 64, mapper);
        transport.subscribe("slow");
        assertThatThrownBy(() -> transport.publish(row("tx-1", 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cap");
        transport.close();
    }

    private static ChangeEvent row(String tx, long id) {
        return ChangeEvent.row("0/" + id, tx, "app", "orders",
                ChangeEvent.ChangeOperation.UPSERT, Map.of("id", id), null, 1L);
    }
}
