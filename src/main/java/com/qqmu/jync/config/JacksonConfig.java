package com.qqmu.jync.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

    /**
     * Customizes Boot's own {@code JsonMapper} rather than defining a second {@code ObjectMapper}
     * bean: Boot 4 auto-configures a primary mapper, and two primaries make by-type injection
     * ambiguous. Jackson 3 ships java.time support in the databind module (no JavaTimeModule).
     */
    @Bean
    public JsonMapperBuilderCustomizer jyncJsonMapperCustomizer() {
        return JacksonConfig::applyDefaults;
    }

    /** The settings every mapper in this application (and its tests) must agree on. */
    public static JsonMapper.Builder applyDefaults(JsonMapper.Builder builder) {
        return builder
                // Jackson 3 writes java.time as ISO strings by default; the 2.7 mapper this
                // application shipped wrote epoch values, and the pages' JavaScript reads them.
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Tolerate configs written by older versions that carried extra fields.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultPropertyInclusion(inclusion ->
                        inclusion.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
