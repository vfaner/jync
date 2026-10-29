package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.qqmu.jync.util.CryptoUtil;
import com.sics.ystream.conf.StartMode;
import com.sics.ystream.conf.YstreamConfig;
import com.sics.ystream.deserializer.DefaultDeserializer;
import com.sics.ystream.result.YstreamLcrInterface;

/**
 * The client library's {@link YstreamConfig} is assembled entirely from the saved source
 * connection plus Jync's own resume position. Nothing else in the codebase touches those
 * types, so this test is the one place the mapping is pinned: a wrong port or an
 * undecrypted password here is a runtime failure at every project start.
 */
class YStreamClientFactoryTest {

    private final CryptoUtil cryptoUtil = mock(CryptoUtil.class);

    private DatabaseConfig yashanSource() {
        DatabaseConfig source = new DatabaseConfig();
        source.setType(DatabaseType.YASHANDB);
        source.setHost("10.1.2.3");
        source.setPort(1700);
        source.setDatabaseName("YASDB");
        source.setUsername("jync_cdc");
        source.setPassword("enc:stored");
        return source;
    }

    private YStreamClientFactory factory() {
        when(cryptoUtil.decrypt("enc:stored")).thenReturn("plain-secret");
        return new YStreamClientFactory(cryptoUtil);
    }

    /**
     * The library reads {@code pollTimeout} in SECONDS (measured: it is passed straight to
     * {@code ArrayBlockingQueue.poll(n, TimeUnit.SECONDS)}). A millisecond value of 1000
     * therefore parks a blocked {@code next()} for over sixteen minutes, and a consumer
     * whose fetch thread died cannot notice for that long.
     */
    @Test
    void thePollTimeoutIsGivenToTheLibraryInSeconds() {
        YstreamConfig<YstreamLcrInterface> config = factory().buildConfig(
                yashanSource(), "JYNC_7_1A2B3C4D", new YStreamPosition(1L, 0, 2L, 3, 4));

        assertThat(config.getPollTimeout())
                .as("a blocked next() must come back in seconds, not minutes")
                .isBetween(1, 60);
    }

    @Test
    void theConfigCarriesTheSourceAndTheRecoverPosition() {
        YStreamPosition recoverFrom = new YStreamPosition(882304965853585408L, 3, 6806L, 5, 7);

        YstreamConfig<YstreamLcrInterface> config = factory()
                .buildConfig(yashanSource(), "JYNC_7_1A2B3C4D", recoverFrom);

        assertThat(config.getServerName()).isEqualTo("JYNC_7_1A2B3C4D");
        assertThat(config.getHost()).isEqualTo("10.1.2.3");
        assertThat(config.getPort()).isEqualTo("1700");
        assertThat(config.getUser()).isEqualTo("jync_cdc");
        assertThat(config.getPassword()).isEqualTo("plain-secret");
        assertThat(config.getDatabase()).isEqualTo("YASDB");
        // RECOVER without a position makes build() throw, so the mode and the tuple are
        // one decision: a resume point is the only way this client ever opens.
        assertThat(config.getStartMode()).isEqualTo(StartMode.RECOVER);
        assertThat(config.getRecoverPosition()).isNotNull();
        assertThat(config.getRecoverPosition().getCommitScn().getScn())
                .isEqualTo(882304965853585408L);
        assertThat(config.getRecoverPosition().getLogPosition().getInstanceId())
                .isEqualTo((byte) 3);
        assertThat(config.getRecoverPosition().getLogPosition().getGroupLsn())
                .isEqualTo(6806L);
        assertThat(config.getRecoverPosition().getLogPosition().getGroupOffset()).isEqualTo(5);
        assertThat(config.getRecoverPosition().getLogPosition().getBatchRowId()).isEqualTo(7);
    }

    @Test
    void theStreamDeserializesIntoLcrsNotRawBytes() {
        YstreamConfig<YstreamLcrInterface> config = factory().buildConfig(
                yashanSource(), "JYNC_7_1A2B3C4D", new YStreamPosition(1L, 0, 2L, 3, 4));

        assertThat(config.getDeserializer()).isInstanceOf(DefaultDeserializer.class);
    }

    @Test
    void aBlankHostAndMissingPortFallBackToLocalhostAndThePresetPort() {
        DatabaseConfig source = yashanSource();
        source.setHost("  ");
        source.setPort(null);

        YstreamConfig<YstreamLcrInterface> config = factory().buildConfig(
                source, "JYNC_7_1A2B3C4D", new YStreamPosition(1L, 0, 2L, 3, 4));

        assertThat(config.getHost()).isEqualTo("localhost");
        assertThat(config.getPort()).isEqualTo(String.valueOf(DatabaseType.YASHANDB.getDefaultPort()));
    }

    @Test
    void aMissingRecoverPositionIsRefusedBeforeTheLibraryCanThrow() {
        // 不带位点的 RECOVER 会在库内部 build() 时报错,信息对用户毫无意义;
        // 这里提前给出 Jync 自己的结论。
        assertThatThrownBy(() -> factory().buildConfig(yashanSource(), "JYNC_7_1A2B3C4D", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position");
    }

    @Test
    void theClientPositionRoundTripsThroughOurOwnTuple() {
        YStreamPosition original = new YStreamPosition(42L, 9, 1000L, 2, 3);

        YStreamPosition back = YStreamClientFactory.fromClientPosition(
                YStreamClientFactory.toClientPosition(original));

        assertThat(back).isEqualTo(original);
    }
}
