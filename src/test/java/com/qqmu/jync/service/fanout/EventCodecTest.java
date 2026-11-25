package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Both transports move events as Java-serialized bytes; the round trip has to keep the value
 * types the applier binds (byte[] for binary columns, BigDecimal for NUMBER/DECIMAL, a
 * Timestamp for time columns) — a JSON text form would quietly change them.
 */
class EventCodecTest {

    @Test
    void aRowEventSurvivesTheWireFormatWithItsValueTypes() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("amount", new BigDecimal("12.50"));
        row.put("payload", new byte[] {1, 2, 3});
        row.put("created", Timestamp.valueOf("2026-11-25 12:00:00"));
        ChangeEvent event = ChangeEvent.row("0x1", "tx", "src", "orders",
                ChangeEvent.ChangeOperation.UPSERT, row, null, 1L);

        ChangeEvent decoded = EventCodec.deserialize(EventCodec.serialize(event));

        // Record equality is not usable here: Map.equals compares the byte[] by identity.
        assertThat(decoded.kind()).isEqualTo(event.kind());
        assertThat(decoded.transactionId()).isEqualTo("tx");
        assertThat(decoded.row().get("id")).isEqualTo(7L);
        assertThat(decoded.row().get("amount")).isEqualTo(new BigDecimal("12.50"));
        assertThat(decoded.row().get("payload")).isEqualTo(new byte[] {1, 2, 3});
        assertThat(decoded.row().get("created"))
                .isEqualTo(Timestamp.valueOf("2026-11-25 12:00:00"));
    }

    @Test
    void anObjectOutsideTheEventGraphIsRefused() {
        byte[] payload = serializeForeignObject();

        assertThatThrownBy(() -> EventCodec.deserialize(payload))
                .isInstanceOf(java.io.IOException.class);
    }

    private static byte[] serializeForeignObject() {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out =
                         new java.io.ObjectOutputStream(buffer)) {
                out.writeObject(new java.util.concurrent.atomic.AtomicInteger(1));
            }
            return buffer.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCommitMarkerKeepsItsPositionAndKind() throws Exception {
        ChangeEvent marker = ChangeEvent.commit("lsn:0/16B3748", "tx", 9L);

        ChangeEvent decoded = EventCodec.deserialize(EventCodec.serialize(marker));

        assertThat(decoded.kind()).isEqualTo(ChangeEvent.EventKind.COMMIT);
        assertThat(decoded.position()).isEqualTo("lsn:0/16B3748");
    }
}
