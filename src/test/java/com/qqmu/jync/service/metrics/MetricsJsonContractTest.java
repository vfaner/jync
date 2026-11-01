package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.qqmu.jync.config.JacksonConfig;

/**
 * The wire contract the performance pages' poller reads.
 *
 * <p>These views are serialised by the application's own {@link ObjectMapper} — the
 * {@code @Primary} one in {@link JacksonConfig}, which the MVC converter is handed — and that
 * mapper writes {@link Instant} as a decimal epoch unless the field says otherwise.
 * {@code metrics.js} feeds {@code lastSample} straight to {@code new Date()}, which reads a
 * number as milliseconds and lands in 1970. The timestamp therefore has to travel as an ISO
 * string, and this test pins it at the mapper the browser actually talks to.
 */
class MetricsJsonContractTest {

    private static final Instant SAMPLED_AT = Instant.parse("2026-11-01T12:34:56.123456Z");

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    void theTaskViewSerialisesItsTimestampAsAnIsoString() throws Exception {
        String json = mapper.writeValueAsString(new ProjectMetricsView(SAMPLED_AT, "RUNNING",
                List.of(), Map.of(), List.of(), List.of()));

        assertThat(json).contains("\"lastSample\":\"2026-11-01T12:34:56");
    }

    @Test
    void theDatabaseViewSerialisesItsTimestampAsAnIsoString() throws Exception {
        String json = mapper.writeValueAsString(new DbMetricsView(true, null, SAMPLED_AT,
                100.0, List.of(), Map.of()));

        assertThat(json).contains("\"lastSample\":\"2026-11-01T12:34:56");
    }
}
