package com.qqmu.jync.service.cdc;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.qqmu.jync.util.CryptoUtil;
import com.sics.ystream.YstreamClientBoot;
import com.sics.ystream.conf.StartMode;
import com.sics.ystream.conf.YstreamConfig;
import com.sics.ystream.deserializer.DefaultDeserializer;
import com.sics.ystream.result.LogPosition;
import com.sics.ystream.result.Position;
import com.sics.ystream.result.SystemChangeNumber;
import com.sics.ystream.result.YstreamLcrInterface;

import lombok.extern.slf4j.Slf4j;

/**
 * Opens a YStream client for one project's service.
 *
 * <p>Mirrors {@link BinlogClientFactory}: the saved source config supplies host, port,
 * credentials and database (with the same {@code localhost} / preset-port fallbacks), and
 * the password is decrypted here — the client library never sees the stored ciphertext.
 *
 * <p>Every client opens in {@link StartMode#RECOVER} with Jync's own stored position: the
 * server's checkpoint only reflects its last periodic flush, so it is not the authority on
 * where the target's data stands (see the spec's position model).
 */
@Slf4j
@Component
public class YStreamClientFactory {

    /** How long one {@code next()} blocks before reporting an idle poll. */
    static final int POLL_TIMEOUT_MS = 1_000;
    /** LCRs the client may buffer internally before applying back-pressure. */
    static final int QUEUE_SIZE = 1_000;

    private final CryptoUtil cryptoUtil;

    public YStreamClientFactory(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    /**
     * Opens the stream at {@code recoverFrom}.
     *
     * @throws Exception from {@code open()} — the consumer classifies it (a link failure
     *         retries with backoff, a rejected position parks the project)
     */
    public YStreamClientAdapter open(DatabaseConfig source, String serviceName,
                                     YStreamPosition recoverFrom) throws Exception {
        YstreamClientBoot<YstreamLcrInterface> client = YstreamClientBoot.getClient();
        try {
            client.open(buildConfig(source, serviceName, recoverFrom));
        } catch (Exception | Error e) {
            // A half-opened client holds sockets and fetch threads; close before the
            // reconnect loop builds the next one.
            try {
                client.close();
            } catch (RuntimeException ignored) {
                log.debug("Closing a failed YStream client attempt threw; ignoring", ignored);
            }
            throw e;
        }
        log.info("Opened YStream stream '{}' at {}", serviceName, recoverFrom.text());
        return new YstreamLcrAdapter(client);
    }

    /**
     * The library config for one stream. Package-private so the mapping is unit-testable
     * without a source.
     *
     * @throws IllegalArgumentException when {@code recoverFrom} is missing — RECOVER
     *         without a position throws inside the library with a message that means
     *         nothing to an operator
     */
    YstreamConfig<YstreamLcrInterface> buildConfig(DatabaseConfig source, String serviceName,
                                                   YStreamPosition recoverFrom) {
        if (recoverFrom == null) {
            throw new IllegalArgumentException(
                    "YStream recovery requires a stored resume position");
        }
        String host = source.getHost() == null || source.getHost().trim().isEmpty()
                ? "localhost" : source.getHost().trim();
        int port = source.getPort() != null
                ? source.getPort() : source.getType().getDefaultPort();
        return YstreamConfig.<YstreamLcrInterface>builder()
                .setServerName(serviceName)
                .setHost(host)
                .setPort(String.valueOf(port))
                .setUser(source.getUsername())
                .setPassword(cryptoUtil.decrypt(source.getPassword()))
                .setDatabase(source.getDatabaseName())
                .setStartMode(StartMode.RECOVER)
                .setRecoverPosition(toClientPosition(recoverFrom))
                .setDeserializer(new DefaultDeserializer())
                .setPollTimeout(POLL_TIMEOUT_MS)
                .setQueueSize(QUEUE_SIZE)
                .build();
    }

    /** Our tuple as the client's position type. */
    static Position toClientPosition(YStreamPosition position) {
        return new Position(new SystemChangeNumber(position.commitScn()),
                new LogPosition((byte) position.instanceId(), position.groupLsn(),
                        position.groupOffset(), position.batchRowId()));
    }

    /** The client's position back as our tuple; null in, null out. */
    static YStreamPosition fromClientPosition(Position position) {
        if (position == null || position.getCommitScn() == null
                || position.getLogPosition() == null) {
            return null;
        }
        LogPosition log = position.getLogPosition();
        return new YStreamPosition(position.getCommitScn().getScn(),
                log.getInstanceId() & 0xFF, log.getGroupLsn(),
                log.getGroupOffset(), log.getBatchRowId());
    }
}
