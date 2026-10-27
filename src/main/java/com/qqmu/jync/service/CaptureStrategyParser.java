package com.qqmu.jync.service;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * The single read of a project's stored capture strategy.
 *
 * <p>Three call sites used to carry byte-identical private copies (a mapper plus a
 * degrade-to-POLLING try/catch): {@code ProjectService}, {@code CdcOperationsService} and
 * {@code CdcConsumerManager}. A skew between those copies would be worse than the
 * duplication — the manager seeing {@code BINLOG_CDC} while a save path degrades to
 * {@code POLLING} misfires the whole CDC switch sequence on an unchanged strategy.
 *
 * <p>Unknown JSON fields are tolerated ({@link SyncConfig} also carries
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so this is belt-and-braces); absent,
 * blank or corrupt config degrades to {@link CaptureStrategy#POLLING}, so a broken row can
 * never silently claim a CDC consumer.
 */
@Slf4j
public final class CaptureStrategyParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CaptureStrategyParser() {
    }

    public static CaptureStrategy parse(String json) {
        return parse(json, null);
    }

    /** @param projectId when known, only used to make the degrade warning actionable. */
    public static CaptureStrategy parse(String json, Long projectId) {
        if (json == null || json.isBlank()) {
            return CaptureStrategy.POLLING;
        }
        try {
            return MAPPER.readValue(json, SyncConfig.class).getCaptureStrategyOrDefault();
        } catch (IOException | RuntimeException e) {
            if (projectId != null) {
                log.warn("Project {} has unreadable sync_config ({}); treating it as POLLING",
                        projectId, e.getMessage());
            } else {
                log.warn("Unreadable sync_config while resolving the capture strategy ({});"
                        + " treating it as POLLING", e.getMessage());
            }
            return CaptureStrategy.POLLING;
        }
    }
}
