package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CdcPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SyncProperties.class)
    static class PropsConfig { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropsConfig.class));

    @Test
    void defaultsMatchTaskBook() {
        SyncProperties.Cdc cdc = new SyncProperties().getCdc();
        assertThat(cdc.getReconnectBackoffMs()).isEqualTo(1_000L);      // 任务书 B7:1s 起
        assertThat(cdc.getReconnectBackoffMaxMs()).isEqualTo(60_000L);   // 上限 60s
        assertThat(cdc.getServerIdOffset()).isEqualTo(612_000L);
        assertThat(cdc.getLockTtlMs()).isEqualTo(30_000L);               // 崩溃接管 ≤60s 的前提
        assertThat(cdc.getSuperviseIntervalMs()).isEqualTo(10_000L);
        assertThat(cdc.getConnectTimeoutMs()).isEqualTo(10_000L);
        assertThat(cdc.getChangeWindowMs()).isEqualTo(10_000L);              // 聚合变更日志 10s 窗口
    }

    @Test
    void yamlKeysBindIntoNestedCdc() {
        runner.withPropertyValues(
                        "sync.cdc.reconnect-backoff-ms=250",
                        "sync.cdc.server-id-offset=700000",
                        "sync.cdc.lock-ttl-ms=5000",
                        "sync.cdc.change-window-ms=0")
                .run(context -> {
                    SyncProperties props = context.getBean(SyncProperties.class);
                    assertThat(props.getCdc().getReconnectBackoffMs()).isEqualTo(250L);
                    assertThat(props.getCdc().getServerIdOffset()).isEqualTo(700_000L);
                    assertThat(props.getCdc().getLockTtlMs()).isEqualTo(5_000L);
                    assertThat(props.getCdc().getChangeWindowMs()).isZero();   // 0=关闭聚合日志
                });
    }

    @Test
    void logminerDefaultsAndBinding() {
        SyncProperties.Cdc.Logminer defaults = new SyncProperties().getCdc().getLogminer();
        assertThat(defaults.getBatchSize()).isEqualTo(10_000L);
        assertThat(defaults.getIdlePollMs()).isEqualTo(1_000L);

        runner.withPropertyValues(
                        "sync.cdc.logminer.batch-size=500",
                        "sync.cdc.logminer.idle-poll-ms=250")
                .run(context -> {
                    SyncProperties.Cdc.Logminer lm =
                            context.getBean(SyncProperties.class).getCdc().getLogminer();
                    assertThat(lm.getBatchSize()).isEqualTo(500L);
                    assertThat(lm.getIdlePollMs()).isEqualTo(250L);
                });
    }
}
