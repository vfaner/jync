package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.scheduling.annotation.Scheduled;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.MetricsProperties;

/** The capacity sweep is the expensive one: it must not ride along with every probe. */
@ExtendWith(MockitoExtension.class)
class DatabaseMetricsSamplerTest {

    @Mock private DatabaseSample databaseSample;

    private MetricsProperties properties;
    private MutableClock clock;
    private DatabaseMetricsSampler sampler;

    @BeforeEach
    void setUp() {
        properties = new MetricsProperties();
        clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
        sampler = new DatabaseMetricsSampler(databaseSample, properties, clock);
    }

    @Test
    void capacityIsCollectedOncePerInterval() {
        sampler.sampleDatabases();
        clock.advance(Duration.ofSeconds(30));
        sampler.sampleDatabases();
        clock.advance(Duration.ofHours(1));
        sampler.sampleDatabases();

        verify(databaseSample).sample(Instant.parse("2026-11-01T10:00:00Z"), true);
        verify(databaseSample).sample(Instant.parse("2026-11-01T10:00:30Z"), false);
        verify(databaseSample).sample(Instant.parse("2026-11-01T11:00:30Z"), true);
    }

    @Test
    void aTurnedOffSamplerWritesNothing() {
        properties.setEnabled(false);

        sampler.sampleDatabases();

        verify(databaseSample, never()).sample(any(), anyBoolean());
    }

    @Test
    void aFailingRoundIsContained() {
        when(databaseSample.sample(any(), anyBoolean())).thenThrow(new IllegalStateException("x"));

        assertThatCode(() -> sampler.sampleDatabases()).doesNotThrowAnyException();
    }

    /**
     * A restart must not leave the performance page empty for a whole interval: the first
     * round rides on its own, short delay instead of the 5-minute cadence. The defaults are
     * pinned here because neither value is reachable from a unit test otherwise.
     */
    @Test
    void theFirstRoundIsScheduledShortlyAfterStartup() throws Exception {
        Scheduled scheduled = DatabaseMetricsSampler.class
                .getDeclaredMethod("sampleDatabases").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${metrics.db-interval-ms:300000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${metrics.db-initial-delay-ms:30000}");
        assertThat(new MetricsProperties().getDbInitialDelayMs()).isEqualTo(30_000L);
    }

    /** A clock the test can move; the sampler never reads the wall clock directly. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
