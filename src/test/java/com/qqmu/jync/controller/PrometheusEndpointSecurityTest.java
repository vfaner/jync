package com.qqmu.jync.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A Prometheus server cannot hold a Jync session, so the scrape path must pass the security
 * chain without a login. The bearer token — not the session — decides who may read it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "metrics.prometheus-token=test-token",
        "spring.datasource.url=jdbc:h2:mem:promtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class PrometheusEndpointSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void theScrapePathAnswersWithoutASessionWhenTheBearerTokenMatches() throws Exception {
        mvc.perform(get("/api/metrics/prometheus")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jync_up 1")));
    }

    @Test
    void theScrapePathStillRejectsARequestWithoutTheToken() throws Exception {
        mvc.perform(get("/api/metrics/prometheus"))
                .andExpect(status().isUnauthorized());
    }
}
