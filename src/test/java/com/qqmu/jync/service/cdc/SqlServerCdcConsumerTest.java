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

    /**
     * 位置列必须是 CONVERT(varchar(32), __$start_lsn, 1) 的规范形式,不能直接选裸 binary(10)。
     * 实测(mssql-jdbc 12.10.2 + SQL Server 2022):getString 读 binary(10) 返回无 0x 前缀的
     * 大写十六进制("0000002857AE0258000A"),而回绑用的是 CONVERT(binary(10), ?, 1) ——
     * Style 1 要求 0x 前缀,无前缀直接 Msg 8114 "Error converting data type varchar to
     * varbinary"。该值被 advanceLsn 持久化后,进程一重启就会每个周期都转换失败,最终
     * APPLY_STRIKE_OUT 把项目钉在 ERROR(只能全量重基线恢复)。
     */
    @Test
    void changePullProjectsStartLsnInItsCanonicalConvertForm() {
        assertThat(SqlServerCdcConsumer.changesSql("dbo_orders_CT"))
                .startsWith("SELECT CONVERT(varchar(32), __$start_lsn, 1), __$operation, *");
        assertThat(SqlServerCdcConsumer.changesSqlInclusive("dbo_orders_CT"))
                .startsWith("SELECT CONVERT(varchar(32), __$start_lsn, 1), __$operation, *");
    }

    /**
     * 源端当前捕获 LSN 落在已持久化水位「之后」—— 源库被还原/回滚到更早的时间点,或项目
     * 改指向另一台源库。此时水位之上的变更在源端根本不存在,{@code fn_cdc_get_all_changes_*}
     * 对倒置区间抛 Msg 313,重试永远是同一个错误:必须判定为「不可恢复」而不是无限重试。
     */
    @Test
    void aSourceWatermarkBehindTheStoredPositionIsDetected() {
        String watermark = "0x0000002857ae0258000a";
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                watermark, "0x00000000000000000000")).isTrue();
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                watermark, "0x0000002857ae02580009")).isTrue();
    }

    @Test
    void aSourceWatermarkAtOrAheadOfTheStoredPositionIsNormal() {
        String watermark = "0x0000002857ae0258000a";
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(watermark, watermark)).isFalse();
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                watermark, "0x0000002857ae0258000b")).isFalse();
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                watermark, "0x00000030000000000000")).isFalse();
    }

    /**
     * 无法识别的持久化水位形态不得被读成「时间线倒退」—— 它必须照常送到 SQL Server 上
     * 显式失败(Msg 8114),而不是被静默改写或误判成 PURGED。
     */
    @Test
    void anUnrecognisedStoredWatermarkYieldsNoVerdict() {
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                "not-an-lsn", "0x00000000000000000000")).isFalse();
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                null, "0x00000000000000000000")).isFalse();
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                "0x0000002857ae0258", "0x00000000000000000000")).isFalse();
    }

    /** 大小写/前缀不同但形态合法(20 位十六进制)—— 归一化后照常比较。 */
    @Test
    void legacyUppercaseWatermarksAreNormalisedBeforeComparing() {
        assertThat(SqlServerCdcConsumer.watermarkBeyondSource(
                "0000002857AE0258000A", "0x00000000000000000000")).isTrue();
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
