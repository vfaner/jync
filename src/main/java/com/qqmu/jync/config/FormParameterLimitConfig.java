package com.qqmu.jync.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lifts the embedded container's per-request parameter limit.
 *
 * <p>The object picker posts one {@code mappingSource} / {@code mappingTarget} pair per table
 * row (and one checkbox per selected object). A schema with a few thousand tables easily
 * exceeds Tomcat's default {@code maxParameterCount} of 10,000. Tomcat does not fail the
 * request when that happens: it parses the first 10,000 parameters, logs once, and silently
 * ignores the rest. The controller then sees an empty {@code tables} / {@code views} /
 * {@code procedures} list with {@code objectSelectionExplicit=true} and saves "nothing
 * selected" — the user's selection disappears after a save and reload.
 *
 * <p>The limit is a memory guard, not a security boundary: the request body is still capped
 * by {@code server.tomcat.max-http-form-post-size}, raised to 8 MB in {@code application.yml}
 * because URL encoding inflates a CJK identifier to 9 bytes per character. 200,000
 * parameters leaves room for schemas with tens of thousands of objects while keeping the
 * worst-case map bounded.
 */
@Configuration
public class FormParameterLimitConfig {

    /** 10x Tomcat's default; see the class comment for the sizing rationale. */
    static final int MAX_PARAMETER_COUNT = 200_000;

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> formParameterLimit() {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setMaxParameterCount(MAX_PARAMETER_COUNT));
    }
}
