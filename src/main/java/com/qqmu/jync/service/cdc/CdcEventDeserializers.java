package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.io.Serializable;
import java.util.IdentityHashMap;
import java.util.Map;

import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.LRUCache;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.AnnotateRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.BinlogCheckpointEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.DeleteRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.EventHeaderV4Deserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.FormatDescriptionEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.GtidEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.GtidTaggedEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.IntVarEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.MariadbGtidEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.MariadbGtidListEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.NullEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.PreviousGtidSetDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.QueryEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.RotateEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.RowsQueryEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.TableMapEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.TransactionPayloadEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.UpdateRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.WriteRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.XAPrepareEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.XidEventDataDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

/**
 * Builds the {@link EventDeserializer} every CDC binlog client uses, replacing the
 * client library's TIME2 wire decoding, which loses the sign of negative durations
 * (ruling R5 completion).
 *
 * <p>MySQL stores a TIME cell in the row image as offset-binary over
 * {@code 3 + fsp-bytes} big-endian bytes: {@code wire = bias + signed}, where
 * {@code bias = 0x800000 << (8 * fspBytes)} and {@code signed} packs the magnitude
 * as {@code ((hour << 12) | (minute << 6) | second) << fracBits + fractionUnits}
 * (hour max 838, fraction units are {@code 100^(3 - fspBytes)} microseconds).
 * The pinned 0.41.5 client ignores the sign entirely: it slices bits 12..21 of the
 * raw wire word as an unsigned hour. For {@code -10:30:00} (wire {@code 0x7F5880})
 * it hands the pipeline {@code +1013:34:00} (3,648,840,000,000 micros) — beyond
 * MySQL's TIME domain, so target writes fail loudly — while {@code -838:59:59}
 * decodes to a plausible {@code 185:04:05} and would corrupt silently.
 *
 * <p>No post-hoc repair is possible in {@link CdcRowMapper}: the sign bit is masked
 * away before the Long reaches the mapper, and the garbled (h, m, s) slice of a
 * negative value overlaps the legitimate positive range, so the corruption is not
 * injective. The wire decoding itself is replaced here instead; the mapper keeps
 * receiving signed duration micros under the {@code DATE_AND_TIME_AS_LONG_MICRO}
 * contract {@link BinlogClientFactory} pins.
 *
 * <p>The full registration map is replicated because the library's four-argument
 * {@link EventDeserializer} constructor — the only public seam for swapping the row
 * deserializers that share its internal table map by reference — requires the
 * COMPLETE map. The mirror is pinned against 0.41.5's
 * {@code registerDefaultEventDataDeserializers}; {@code CdcEventDeserializersTest}
 * fails loudly if it ever drifts from the library's own defaults.
 *
 * <p>Compressed transactions ({@code binlog_transaction_compression}) are unpacked by
 * `CdcEventStreamHandler` with {@link #newClientDeserializer()},
 * so the payload does not fall back to the stock, sign-dropping TIME2 decoder.
 */
final class CdcEventDeserializers {

    private CdcEventDeserializers() {
    }

    /**
     * The deserializer used for the live client and for the events inside a compressed
     * transaction payload (which the library would otherwise parse with the stock,
     * sign-dropping TIME2 decoder).
     */
    static EventDeserializer newClientDeserializer() {
        EventDeserializer deserializer = newDeserializer();
        deserializer.setCompatibilityMode(
                EventDeserializer.CompatibilityMode.DATE_AND_TIME_AS_LONG_MICRO,
                EventDeserializer.CompatibilityMode.INVALID_DATE_AND_TIME_AS_MIN_VALUE,
                EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY);
        return deserializer;
    }

    /** Event deserializer with signed TIME2 decoding; compatibility modes set by the caller. */
    static EventDeserializer newDeserializer() {
        Map<Long, TableMapEventData> tableMapEventByTableId = new LRUCache<>(100, 0.75f, 10000);
        Map<EventType, EventDataDeserializer> eventDataDeserializers = new IdentityHashMap<>();
        // Mirror of EventDeserializer#registerDefaultEventDataDeserializers (0.41.5),
        // with the six row-event deserializers swapped for signed-TIME-aware subclasses.
        eventDataDeserializers.put(EventType.FORMAT_DESCRIPTION, new FormatDescriptionEventDataDeserializer());
        eventDataDeserializers.put(EventType.ROTATE, new RotateEventDataDeserializer());
        eventDataDeserializers.put(EventType.INTVAR, new IntVarEventDataDeserializer());
        eventDataDeserializers.put(EventType.QUERY, new QueryEventDataDeserializer());
        eventDataDeserializers.put(EventType.TABLE_MAP, new TableMapEventDataDeserializer());
        eventDataDeserializers.put(EventType.XID, new XidEventDataDeserializer());
        eventDataDeserializers.put(EventType.WRITE_ROWS, new SignedTimeV2WriteRows(tableMapEventByTableId));
        eventDataDeserializers.put(EventType.UPDATE_ROWS, new SignedTimeV2UpdateRows(tableMapEventByTableId));
        eventDataDeserializers.put(EventType.DELETE_ROWS, new SignedTimeV2DeleteRows(tableMapEventByTableId));
        eventDataDeserializers.put(EventType.EXT_WRITE_ROWS,
                new SignedTimeV2WriteRows(tableMapEventByTableId).setMayContainExtraInformation(true));
        eventDataDeserializers.put(EventType.EXT_UPDATE_ROWS,
                new SignedTimeV2UpdateRows(tableMapEventByTableId).setMayContainExtraInformation(true));
        eventDataDeserializers.put(EventType.EXT_DELETE_ROWS,
                new SignedTimeV2DeleteRows(tableMapEventByTableId).setMayContainExtraInformation(true));
        eventDataDeserializers.put(EventType.ROWS_QUERY, new RowsQueryEventDataDeserializer());
        eventDataDeserializers.put(EventType.GTID, new GtidEventDataDeserializer());
        eventDataDeserializers.put(EventType.GTID_TAGGED, new GtidTaggedEventDataDeserializer());
        eventDataDeserializers.put(EventType.PREVIOUS_GTIDS, new PreviousGtidSetDeserializer());
        eventDataDeserializers.put(EventType.XA_PREPARE, new XAPrepareEventDataDeserializer());
        eventDataDeserializers.put(EventType.ANNOTATE_ROWS, new AnnotateRowsEventDataDeserializer());
        eventDataDeserializers.put(EventType.MARIADB_GTID, new MariadbGtidEventDataDeserializer());
        eventDataDeserializers.put(EventType.BINLOG_CHECKPOINT, new BinlogCheckpointEventDataDeserializer());
        eventDataDeserializers.put(EventType.MARIADB_GTID_LIST, new MariadbGtidListEventDataDeserializer());
        eventDataDeserializers.put(EventType.TRANSACTION_PAYLOAD, new TransactionPayloadEventDataDeserializer());
        return new EventDeserializer(new EventHeaderV4Deserializer(), new NullEventDataDeserializer(),
                eventDataDeserializers, tableMapEventByTableId);
    }

    /**
     * Decodes the raw TIME2 wire bytes (3 + fraction bytes, big-endian) into SIGNED
     * duration microseconds — the DATE_AND_TIME_AS_LONG_MICRO contract, minus the
     * sign loss. Offset-binary: subtracting the bias restores the signed packed
     * value; the magnitude then splits into (hour:10, minute:6, second:6) bit
     * fields plus the fraction in {@code 100^(3 - fracBytes)} microsecond units.
     */
    static long decodeTimeV2SignedMicros(int meta, byte[] wire) {
        int fractionLength = (meta + 1) / 2;
        long full = 0;
        for (byte b : wire) {
            full = (full << 8) | (b & 0xFFL);
        }
        int fractionBits = 8 * fractionLength;
        long signed = full - (0x800000L << fractionBits);
        long magnitude = Math.abs(signed);
        long hmsBits = magnitude >> fractionBits;
        long fractionScaled = magnitude & ((1L << fractionBits) - 1);
        long fractionMicros = fractionLength == 0
                ? 0L
                : fractionScaled * (long) Math.pow(100, 3 - fractionLength);
        long seconds = ((hmsBits >> 12) & 0x3FF) * 3600L
                + ((hmsBits >> 6) & 0x3F) * 60L
                + (hmsBits & 0x3F);
        long micros = seconds * 1_000_000L + fractionMicros;
        return signed < 0 ? -micros : micros;
    }

    /** Reads the 3 + fraction wire bytes and decodes them with sign (ruling R5). */
    private static Long readTimeV2Signed(int meta, ByteArrayInputStream inputStream) throws IOException {
        int fractionLength = (meta + 1) / 2;
        return decodeTimeV2SignedMicros(meta, inputStream.read(3 + fractionLength));
    }

    static final class SignedTimeV2WriteRows extends WriteRowsEventDataDeserializer {
        SignedTimeV2WriteRows(Map<Long, TableMapEventData> tableMapEventByTableId) {
            super(tableMapEventByTableId);
        }

        @Override
        protected Serializable deserializeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
            return readTimeV2Signed(meta, inputStream);
        }
    }

    static final class SignedTimeV2UpdateRows extends UpdateRowsEventDataDeserializer {
        SignedTimeV2UpdateRows(Map<Long, TableMapEventData> tableMapEventByTableId) {
            super(tableMapEventByTableId);
        }

        @Override
        protected Serializable deserializeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
            return readTimeV2Signed(meta, inputStream);
        }
    }

    static final class SignedTimeV2DeleteRows extends DeleteRowsEventDataDeserializer {
        SignedTimeV2DeleteRows(Map<Long, TableMapEventData> tableMapEventByTableId) {
            super(tableMapEventByTableId);
        }

        @Override
        protected Serializable deserializeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
            return readTimeV2Signed(meta, inputStream);
        }
    }
}
