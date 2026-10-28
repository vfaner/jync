package com.qqmu.jync.service;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseType;

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

    /**
     * The strategy a form submitted, validated against the source it will read.
     *
     * <p>Shared by every save path that accepts a strategy name ({@code ProjectService.saveConfig}
     * and the bulk planner), so the acceptance rules cannot skew between the two. Only the
     * endpoint's own type is accepted for each CDC strategy: the MySQL-wire compatibles speak a
     * different binlog dialect, so a "close enough" type check would route to a consumer that
     * cannot read the stream.
     *
     * @param requested the submitted name, or null/blank to leave the current strategy alone
     * @param sourceType the source connection's product, or null when it cannot be resolved
     * @return the parsed strategy, or null when nothing was submitted
     * @throws IllegalArgumentException with an i18n key the page knows how to translate
     */
    public static CaptureStrategy parseRequested(String requested, DatabaseType sourceType) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        CaptureStrategy strategy;
        try {
            strategy = CaptureStrategy.valueOf(requested.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.project.strategy.invalid");
        }
        if (strategy == CaptureStrategy.BINLOG_CDC && sourceType != DatabaseType.MYSQL) {
            throw new IllegalArgumentException("error.project.cdc.not.mysql");
        }
        if (strategy == CaptureStrategy.LOGMINER_CDC && sourceType != DatabaseType.ORACLE) {
            throw new IllegalArgumentException("error.project.cdc.not.oracle");
        }
        // v1.9.0: PG 家族一律放行到探测。家族成员能否真正使用由连接时的能力探测
        // 决定（openGauss 5.0 已实测；其余内核探测不过即拒绝），这里不按字面产品名拦。
        if (strategy == CaptureStrategy.PG_LOGICAL_CDC
                && (sourceType == null
                        || sourceType.getFamily() != DatabaseType.DialectFamily.POSTGRES)) {
            throw new IllegalArgumentException("error.project.cdc.not.postgres");
        }
        if (strategy == CaptureStrategy.SQLSERVER_CDC && sourceType != DatabaseType.SQLSERVER) {
            throw new IllegalArgumentException("error.project.cdc.not.sqlserver");
        }
        return strategy;
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
