package com.qqmu.jync.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
        return parseRequested(requested, sourceType,
                sourceType == null ? null : sourceType.getFamily());
    }

    /**
     * The same gate, with the source's <em>declared</em> dialect family supplied explicitly.
     *
     * <p>A {@code CUSTOM} connection carries the {@code GENERIC} preset family but declares
     * the family it actually speaks ({@link com.qqmu.jync.model.DatabaseConfig#resolvedFamily()});
     * that declared family is what the dialect, type mapping and metadata reader already
     * follow. The engines that verify themselves against the live server by capability —
     * PG logical CDC, MySQL binlog CDC and, since v1.9.0, LogMiner (DM8 measured) — accept
     * the whole family, so a look-alike without a preset gets the same treatment as a
     * preset member: it passes the gate and then has to pass the probe. SQL Server CDC
     * keeps checking the preset, because its compatibility is unverified and a declared
     * family is not evidence.
     *
     * @param sourceFamily the source's resolved family, or null when it cannot be resolved
     */
    public static CaptureStrategy parseRequested(String requested, DatabaseType sourceType,
                                                 DatabaseType.DialectFamily sourceFamily) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        CaptureStrategy strategy;
        try {
            strategy = CaptureStrategy.valueOf(requested.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.project.strategy.invalid");
        }
        // v1.9.0: binlog follows the MySQL family, for the same reason PG does — the
        // engine no longer trusts a product name. Any MySQL-wire source may be saved with
        // BINLOG_CDC, and the capability probe (BinlogCdcProbe) decides at connect time
        // whether its event stream actually decodes; a look-alike that cannot be decoded
        // fails there with the decoder's own message instead of being refused by name.
        if (strategy == CaptureStrategy.BINLOG_CDC
                && sourceFamily != DatabaseType.DialectFamily.MYSQL) {
            throw new IllegalArgumentException("error.project.cdc.not.mysql");
        }
        // v1.9.0: LogMiner 同样改成家族 + 连接时能力探测。DM / 崖山声明 ORACLE 家族，
        // 达梦已实测（V$RLOGFILE / ARCH_MODE / RLOG_APPEND_LOGIC，见 LogMinerCdcProbe），
        // 崖山探测不过会被拒，这里不再按品牌名一票否决。
        if (strategy == CaptureStrategy.LOGMINER_CDC
                && sourceFamily != DatabaseType.DialectFamily.ORACLE) {
            throw new IllegalArgumentException("error.project.cdc.not.oracle");
        }
        // v1.9.0: PG 家族一律放行到探测。家族成员能否真正使用由连接时的能力探测
        // 决定（openGauss 5.0 已实测；其余内核探测不过即拒绝），这里不按字面产品名拦。
        // CUSTOM 连接按它声明的方言家族算 —— 其余链路（方言、类型映射、元数据读取）
        // 早就用 resolvedFamily()，只有这里按预设家族会把「自定义接入的 PG 系产品」
        // 挡在唯一一个靠能力自证的引擎之外。
        if (strategy == CaptureStrategy.PG_LOGICAL_CDC
                && sourceFamily != DatabaseType.DialectFamily.POSTGRES) {
            throw new IllegalArgumentException("error.project.cdc.not.postgres");
        }
        if (strategy == CaptureStrategy.SQLSERVER_CDC && sourceType != DatabaseType.SQLSERVER) {
            throw new IllegalArgumentException("error.project.cdc.not.sqlserver");
        }
        // v1.9.0: YStream 是崖山自带的逻辑日志引擎（DBMS_YSTREAM_ADM），别的产品没有这个
        // 包 —— 达梦同样声明 ORACLE 家族但与之无关。所以这里按产品名精确判，不跟着家族
        // 放行，也不接受 CUSTOM 声明的家族：家族内部没有能力自证的空间。
        if (strategy == CaptureStrategy.YSTREAM_CDC && sourceType != DatabaseType.YASHANDB) {
            throw new IllegalArgumentException("error.project.cdc.not.yashan");
        }
        return strategy;
    }

    /**
     * The strategies the bulk-create page may offer for a source, in display order.
     *
     * <p>Derived from {@link #parseRequested} by construction — each candidate is run
     * through the same gate — so the page can never list an option the save path would
     * refuse. The listing may be <em>narrower</em> than the gate: YashanDB passes the
     * LogMiner family gate yet has no {@code DBMS_LOGMNR} (measured, see
     * {@code docs/dm-logminer-spike.md} §7), and offering it would only produce a probe
     * failure after the user has committed to a batch of tasks.
     *
     * <p>The family is a parameter rather than derived from {@code sourceType} on purpose:
     * a {@code CUSTOM} connection's preset family is {@code GENERIC}, and only
     * {@link com.qqmu.jync.model.DatabaseConfig#resolvedFamily()} knows the family it
     * actually declares. A caller that cannot resolve it passes null and gets the
     * type-agnostic pair, which is the honest answer.
     */
    public static List<CaptureStrategy> supportedFor(DatabaseType sourceType,
                                                     DatabaseType.DialectFamily sourceFamily) {
        List<CaptureStrategy> supported = new ArrayList<>();
        for (CaptureStrategy strategy : CaptureStrategy.values()) {
            if (strategy == CaptureStrategy.LOGMINER_CDC && sourceType == DatabaseType.YASHANDB) {
                continue;
            }
            try {
                parseRequested(strategy.name(), sourceType, sourceFamily);
                supported.add(strategy);
            } catch (IllegalArgumentException e) {
                // Not supported for this source — simply not offered.
            }
        }
        return List.copyOf(supported);
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
