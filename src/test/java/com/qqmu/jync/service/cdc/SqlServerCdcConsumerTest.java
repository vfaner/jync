package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P0-6/P1-7 的 SQL Server drain 侧契约:
 *
 * <ul>
 *   <li>拉取 SQL 必须 ORDER BY __$start_lsn, __$seqval —— {@link SqlServerTxFold}
 *       的折叠语义(同 PK 后到覆盖先到、op=3 配对 op=4)只在源序下有净效果正确性;
 *       fn_cdc_get_all_changes 的默认顺序是文档口径,显式 ORDER BY 才是合同。</li>
 *   <li>capture_instance 名来自源库系统表、直接拼进 SQL —— 必须白名单校验
 *       (SQL Server 标识符此处只会是 [A-Za-z0-9_]),不合名的跳过而不是拼进去。</li>
 * </ul>
 */
class SqlServerCdcConsumerTest {

    @Test
    void changesSqlOrdersByStartLsnAndSeqval() {
        String sql = SqlServerCdcConsumer.changesSql("dbo_orders_CT");
        assertThat(sql).contains("cdc.fn_cdc_get_all_changes_dbo_orders_CT");
        assertThat(sql).contains("'all update old'");
        assertThat(sql).contains("ORDER BY __$start_lsn, __$seqval");
    }

    @Test
    void captureInstanceNamesAreWhitelisted() {
        assertThat(SqlServerCdcConsumer.isSafeCaptureInstanceName("dbo_orders_CT")).isTrue();
        assertThat(SqlServerCdcConsumer.isSafeCaptureInstanceName("schema_1")).isTrue();
        // 拼接注入面:引号、空格、分号、注释一律拒绝。
        assertThat(SqlServerCdcConsumer.isSafeCaptureInstanceName("a' OR 1=1--")).isFalse();
        assertThat(SqlServerCdcConsumer.isSafeCaptureInstanceName("a;DROP TABLE x")).isFalse();
        assertThat(SqlServerCdcConsumer.isSafeCaptureInstanceName("")).isFalse();
        assertThat(SqlServerCdcConsumer.isSafeCaptureInstanceName(null)).isFalse();
    }
}
