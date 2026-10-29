package com.qqmu.jync.service.cdc;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Assembles the {@link BinaryLogClient} for a CDC project (task book B1).
 *
 * <p>The Debezium fork keeps the com.github.shyiko package names and authenticates with
 * caching_sha2_password, so MySQL 8.4+ sources (which removed mysql_native_password)
 * work out of the box.
 *
 * <p>Deserializer compatibility modes pin the wire format for the row mapper:
 * <ul>
 *   <li>{@code DATE_AND_TIME_AS_LONG_MICRO} — temporal cells arrive as epoch-micros
 *       Longs; no java.util.Date timezone guesswork. TIME cells arrive as signed
 *       duration micros via {@link CdcEventDeserializers}, which replaces the stock
 *       TIME2 decoding that loses the sign of negative values.</li>
 *   <li>{@code INVALID_DATE_AND_TIME_AS_MIN_VALUE} — zero dates arrive as
 *       Long.MIN_VALUE, which the mapper turns into null + a warn counter.</li>
 *   <li>{@code CHAR_AND_BINARY_AS_BYTE_ARRAY} — char/binary cells arrive as raw
 *       bytes; the mapper decodes them with the column's own charset.</li>
 * </ul>
 *
 * <p>keepAlive is enabled and a 30s application-level heartbeat is requested so dead
 * peers surface as communication failures; reconnect policy (exponential backoff,
 * fatal classification) still lives entirely in {@link CdcConsumer} so there is
 * exactly one place that decides when to retry.
 */
@Slf4j
@Component
public class BinlogClientFactory {

    private final CryptoUtil cryptoUtil;
    private final SyncProperties properties;

    public BinlogClientFactory(CryptoUtil cryptoUtil, SyncProperties properties) {
        this.cryptoUtil = cryptoUtil;
        this.properties = properties;
    }

    /** The fake replica id this project presents: offset + projectId, never colliding internally. */
    public long serverIdFor(long projectId) {
        return properties.getCdc().getServerIdOffset() + projectId;
    }

    public BinaryLogClient createClient(DatabaseConfig source, long projectId, CdcPosition resumeFrom) {
        BinaryLogClient client = newClient(source);
        client.setServerId(serverIdFor(projectId));
        // #3 fix: without a heartbeat, a half-open TCP (NAT idle cut, source crash with
        // no FIN) leaves the event loop parked on a read — client.isConnected() stays
        // true, the consumer thread never wakes, and the lease never releases. A 30s
        // heartbeat forces the socket to see activity even when the source has no
        // transactions; a missed heartbeat surfaces as a communication failure and the
        // reconnect loop takes over.
        client.setKeepAlive(true);
        try {
            // Unit is MILLISECONDS. The value was 30 for a long time — a 30ms
            // heartbeat means ~33 events/second against an idle source and turns
            // every network hiccup into a reconnect storm.
            client.setHeartbeatInterval(TimeUnit.SECONDS.toMillis(30));
        } catch (java.lang.NoSuchMethodError | RuntimeException e) {
            // Older mysql-binlog-connector forks expose heartbeat only via socket options;
            // keepAlive(true) alone still lets TCP see dead peers — but say so loudly,
            // a silent catch here is how the unit bug stayed hidden.
            log.warn("setHeartbeatInterval unavailable ({}); falling back to keepAlive"
                    + " only — half-open sockets may linger longer", e.toString());
        }
        if (resumeFrom != null) {
            if (resumeFrom.getGtidSet() != null && !resumeFrom.getGtidSet().trim().isEmpty()) {
                client.setGtidSet(resumeFrom.getGtidSet().trim());
            } else {
                client.setBinlogFilename(resumeFrom.getBinlogFile());
                client.setBinlogPosition(resumeFrom.getBinlogPos() == null ? 4L : resumeFrom.getBinlogPos());
            }
        }
        return client;
    }

    /**
     * A one-shot stream for the capability probe: no keep-alive reconnect (a rejected
     * stream must fail once, not retry behind the probe's back) and an explicit position.
     */
    public BinaryLogClient createProbeClient(DatabaseConfig source, long serverId,
                                             String binlogFile, long position) {
        BinaryLogClient client = newClient(source);
        client.setServerId(serverId);
        client.setKeepAlive(false);
        client.setBinlogFilename(binlogFile);
        client.setBinlogPosition(position);
        return client;
    }

    /**
     * The port the binlog connection uses: the configured one, else the type's default —
     * the same rule {@code DataSourceManager.buildJdbcUrl} applies, so binlog and JDBC never
     * talk to different ports. {@code BinaryLogClient} keeps its port private, so this is the
     * only place the fallback can be asserted directly.
     */
    static int resolvePort(DatabaseConfig source) {
        return source.getPort() != null ? source.getPort() : source.getType().getDefaultPort();
    }

    private BinaryLogClient newClient(DatabaseConfig source) {
        String host = source.getHost() == null || source.getHost().trim().isEmpty()
                ? "localhost" : source.getHost().trim();
        int port = resolvePort(source);
        BinaryLogClient client = new BinaryLogClient(host, port,
                source.getUsername(), cryptoUtil.decrypt(source.getPassword()));
        client.setEventDeserializer(deserializer());
        return client;
    }

    private static EventDeserializer deserializer() {
        // Ruling R5 completion: CdcEventDeserializers replaces the stock TIME2 wire
        // decoding, which drops the sign of negative durations (-10:30:00 arrives as
        // +1013:34:00). Every other event type keeps the library default.
        EventDeserializer deserializer = CdcEventDeserializers.newDeserializer();
        deserializer.setCompatibilityMode(
                EventDeserializer.CompatibilityMode.DATE_AND_TIME_AS_LONG_MICRO,
                EventDeserializer.CompatibilityMode.INVALID_DATE_AND_TIME_AS_MIN_VALUE,
                EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY);
        return deserializer;
    }
}
