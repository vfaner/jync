package com.qqmu.jync.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.service.metrics.PrometheusMetricsService;

/**
 * The scrape endpoint is public to the security chain only because a scraper cannot hold a
 * session; the bearer token is the gate, and an empty token means the endpoint is closed.
 */
class PrometheusMetricsControllerTest {

    private static final String PATH = "/api/metrics/prometheus";

    private final PrometheusMetricsService service = mock(PrometheusMetricsService.class);
    private final MetricsProperties properties = new MetricsProperties();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(service.scrape()).thenReturn("jync_up 1\n");
        mvc = MockMvcBuilders.standaloneSetup(
                new PrometheusMetricsController(service, properties)).build();
    }

    @Test
    void anUnconfiguredTokenLeavesTheEndpointClosed() throws Exception {
        properties.setPrometheusToken("");

        mvc.perform(get(PATH))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingOrWrongTokenIsRejected() throws Exception {
        properties.setPrometheusToken("s3cret");

        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get(PATH).header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theConfiguredBearerTokenReturnsTheScrapeBody() throws Exception {
        properties.setPrometheusToken("s3cret");

        mvc.perform(get(PATH).header("Authorization", "Bearer s3cret"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("jync_up 1\n"));
    }

    @Test
    void theSchemeIsCaseInsensitive() throws Exception {
        properties.setPrometheusToken("s3cret");

        mvc.perform(get(PATH).header("Authorization", "bearer s3cret"))
                .andExpect(status().isOk());
    }
}
