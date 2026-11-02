package com.qqmu.jync.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.service.metrics.PrometheusMetricsService;

/**
 * Prometheus scrape endpoint.
 *
 * <p>It is reachable without a Jync session because a scraper cannot log in; the bearer token
 * in {@code metrics.prometheus-token} is the whole gate. An empty token closes the endpoint
 * rather than opening it — a stock install must not publish its project names and connection
 * names to anyone who can reach the port.
 */
@RestController
public class PrometheusMetricsController {

    static final String MEDIA_TYPE = "text/plain;version=0.0.4;charset=UTF-8";

    private final PrometheusMetricsService metrics;
    private final MetricsProperties properties;

    public PrometheusMetricsController(PrometheusMetricsService metrics,
                                       MetricsProperties properties) {
        this.metrics = metrics;
        this.properties = properties;
    }

    @GetMapping(value = "/api/metrics/prometheus", produces = MEDIA_TYPE)
    public ResponseEntity<String> scrape(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization) {
        String token = properties.getPrometheusToken();
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (!matches(token, authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MEDIA_TYPE))
                .body(metrics.scrape());
    }

    /** Constant-time compare; the scheme is case-insensitive per RFC 7235. */
    private static boolean matches(String expected, String authorization) {
        if (authorization == null) {
            return false;
        }
        int space = authorization.indexOf(' ');
        if (space <= 0 || !"Bearer".equalsIgnoreCase(authorization.substring(0, space))) {
            return false;
        }
        String presented = authorization.substring(space + 1).trim();
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
