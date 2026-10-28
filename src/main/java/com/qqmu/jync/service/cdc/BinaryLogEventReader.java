package com.qqmu.jync.service.cdc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.qqmu.jync.model.DatabaseConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link BinlogEventReader} backed by the same client the consumer runs.
 *
 * <p>Reading one event proves three things at once: the account may open a replication
 * stream, the server's protocol handshake is one this client speaks, and its event
 * decoder accepts the bytes — the last is exactly what a version banner cannot promise.
 *
 * <p>Row-level events are preferred: the FormatDescription event is structurally stable
 * across MySQL-compatible servers, while row events are where MariaDB and the distributed
 * products diverge. The reader therefore keeps reading past the format description until
 * it sees a table-map or row event, an event cap (a busy file could otherwise stream for
 * the whole timeout), a timeout, or a decoder failure. A file with no row event in the
 * first {@link #MAX_EVENTS} events still passes on the events it did decode — and the
 * detail string says so, rather than pretending row decoding was proven.
 */
@Slf4j
@Component
public class BinaryLogEventReader implements BinlogEventReader {

    /** Cap on events read before giving up on seeing a row event. */
    private static final int MAX_EVENTS = 200;

    private final BinlogClientFactory clientFactory;

    public BinaryLogEventReader(BinlogClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String readFirstEvent(DatabaseConfig source, String binlogFile, long position,
                                 long serverId, Duration timeout) throws Exception {
        BinaryLogClient client = clientFactory.createProbeClient(
                source, serverId, binlogFile, position);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> rowEvent = new AtomicReference<>();
        AtomicReference<String> anyEvent = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Exception> decodeFailure = new AtomicReference<>();
        AtomicInteger seen = new AtomicInteger();

        client.registerEventListener(event -> {
            try {
                if (event == null) {
                    return;
                }
                String description = describe(event);
                if (description != null) {
                    rowEvent.compareAndSet(null, description);
                    done.countDown();
                    return;
                }
                anyEvent.compareAndSet(null, describeAny(event));
                if (seen.incrementAndGet() >= MAX_EVENTS) {
                    done.countDown();
                }
            } catch (RuntimeException e) {
                // A listener failure must not kill the client thread silently.
                failure.compareAndSet(null, e);
                done.countDown();
            }
        });
        client.registerLifecycleListener(new BinaryLogClient.LifecycleListener() {
            @Override
            public void onConnect(BinaryLogClient c) {
                // nothing to do — the event listener drives completion
            }

            @Override
            public void onCommunicationFailure(BinaryLogClient c, Exception ex) {
                failure.compareAndSet(null, ex);
                done.countDown();
            }

            @Override
            public void onEventDeserializationFailure(BinaryLogClient c, Exception ex) {
                // The real signal on a server whose wire format this client cannot read.
                // Tracked apart from the other failures: only this one means "incompatible
                // format", the others mean "could not open the stream at all".
                decodeFailure.compareAndSet(null, ex);
                done.countDown();
            }

            @Override
            public void onDisconnect(BinaryLogClient c) {
                done.countDown();
            }
        });

        try {
            client.connect(timeout.toMillis());
            if (!done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // Something decoded but no row event arrived in the budget. That is a pass,
                // not a failure: a freshly rotated (or idle) binlog file holds only
                // FormatDescription / GTID / CHECKPOINT events, and failing there reported
                // a perfectly healthy source as "cannot be decoded" — measured on MariaDB
                // 11.8, whose current file carried no row events at all.
                failIfAny(decodeFailure, failure);
                String first = anyEvent.get();
                if (first == null) {
                    throw new TimeoutException("connected to the binlog stream but decoded no"
                            + " event within " + timeout.toSeconds() + "s");
                }
                return first + " (no row event before the stream probe timed out)";
            }
        } finally {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.debug("Probe binlog client did not disconnect cleanly: {}", e.toString());
            }
        }

        failIfAny(decodeFailure, failure);
        String decoded = rowEvent.get();
        if (decoded != null) {
            return decoded;
        }
        String first = anyEvent.get();
        if (first == null) {
            throw new Exception("the stream ended without delivering a single decodable event");
        }
        return first + " (no row event within " + MAX_EVENTS + " events)";
    }

    /**
     * Raise whatever the client reported, decoder failures first — they are the only ones
     * with an actionable meaning for the caller ("this format is not readable").
     */
    private static void failIfAny(AtomicReference<Exception> decodeFailure,
                                  AtomicReference<Exception> failure) throws Exception {
        Exception decode = decodeFailure.get();
        if (decode != null) {
            throw new CdcDecodeException(message(decode), decode);
        }
        Exception error = failure.get();
        if (error != null) {
            throw new Exception(message(error), error);
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** Row-level events are the ones that prove the product's row format decodes. */
    private static String describe(Event event) {
        EventData data = event.getData();
        if (data instanceof TableMapEventData tableMap) {
            return "TableMapEvent (table " + tableMap.getTable() + ")"
                    + eventHeader(event);
        }
        if (data instanceof WriteRowsEventData) {
            return "WriteRowsEvent" + eventHeader(event);
        }
        if (data instanceof UpdateRowsEventData) {
            return "UpdateRowsEvent" + eventHeader(event);
        }
        if (data instanceof DeleteRowsEventData) {
            return "DeleteRowsEvent" + eventHeader(event);
        }
        return null;
    }

    private static String describeAny(Event event) {
        EventData data = event.getData();
        String type = data == null ? "?" : data.getClass().getSimpleName();
        return type + eventHeader(event);
    }

    private static String eventHeader(Event event) {
        return event.getHeader() == null || event.getHeader().getEventType() == null
                ? "" : " (" + event.getHeader().getEventType() + ")";
    }
}
