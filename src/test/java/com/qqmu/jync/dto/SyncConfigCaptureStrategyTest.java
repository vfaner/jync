package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.CaptureStrategy;

/**
 * captureStrategy 是 v1.5.0 新字段。存量项目的 sync_config JSON 里没有它,反序列化后
 * 必须是 null 且 getCaptureStrategyOrDefault() 落到 POLLING —— 升级后行为与 v1.4.0
 * 完全一致(任务书兼容性表第一行)。
 */
class SyncConfigCaptureStrategyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyJsonWithoutFieldStaysPolling() throws Exception {
        SyncConfig config = mapper.readValue(
                "{\"tables\":[\"orders\"],\"syncDeletes\":true,\"batchSize\":100}", SyncConfig.class);
        assertThat(config.getCaptureStrategy()).isNull();
        assertThat(config.getCaptureStrategyOrDefault()).isEqualTo(CaptureStrategy.POLLING);
        assertThat(config.getBatchSize()).isEqualTo(100);
    }

    @Test
    void explicitStrategyRoundTrips() throws Exception {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        String json = mapper.writeValueAsString(config);
        assertThat(json).contains("\"captureStrategy\":\"BINLOG_CDC\"");
        SyncConfig back = mapper.readValue(json, SyncConfig.class);
        assertThat(back.getCaptureStrategyOrDefault()).isEqualTo(CaptureStrategy.BINLOG_CDC);
    }

    @Test
    void unknownFutureFieldsStillIgnored() throws Exception {
        // 向前兼容:新版本写出的 JSON 带回老版本不认识的键时,老版本仍能解析(现有 Jackson 配置)
        SyncConfig config = mapper.readValue(
                "{\"captureStrategy\":\"FULL_COMPARE\",\"someFutureKey\":42}", SyncConfig.class);
        assertThat(config.getCaptureStrategyOrDefault()).isEqualTo(CaptureStrategy.FULL_COMPARE);
    }

    @Test
    void logminerCdcRoundTripsAndIsLogCdc() throws Exception {
        SyncConfig config = mapper.readValue("{\"captureStrategy\":\"LOGMINER_CDC\"}", SyncConfig.class);
        assertThat(config.getCaptureStrategyOrDefault()).isEqualTo(CaptureStrategy.LOGMINER_CDC);
        assertThat(CaptureStrategy.LOGMINER_CDC.isLogCdc()).isTrue();
        assertThat(CaptureStrategy.BINLOG_CDC.isLogCdc()).isTrue();
        assertThat(CaptureStrategy.POLLING.isLogCdc()).isFalse();
        assertThat(CaptureStrategy.FULL_COMPARE.isLogCdc()).isFalse();
    }
}
