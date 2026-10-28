package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * v1.9.0: PG 逻辑 CDC 的门禁按方言家族判，不再按字面产品名。家族成员能否真正使用
 * 由连接时的能力探测决定（见 PgLogicalReplicationCdcProbe）。
 */
class CaptureStrategyParserTest {

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {
            "POSTGRESQL", "OPENGAUSS", "GAUSSDB", "KINGBASE", "RASESQL",
            "SEABOXSQL", "HIGHGO", "VASTBASE", "OSCAR"})
    void everyPostgresFamilyPresetAcceptsPgLogicalCdc(DatabaseType type) {
        assertThat(CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC", type))
                .isEqualTo(CaptureStrategy.PG_LOGICAL_CDC);
    }

    @Test
    void nonPostgresFamilyStillRejectsPgLogicalCdc() {
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC", DatabaseType.MYSQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC", DatabaseType.ORACLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
    }

    @Test
    void binlogAndLogMinerFollowTheirFamilies() {
        // v1.9.0: binlog 与 PG 逻辑 CDC 一样改为家族 + 连接时事件探测，MySQL 系兼容库
        // （MariaDB / TiDB / OceanBase …）不再在保存时被品牌名一票否决。
        assertThat(CaptureStrategyParser.parseRequested("BINLOG_CDC", DatabaseType.MARIADB))
                .isEqualTo(CaptureStrategy.BINLOG_CDC);
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("BINLOG_CDC", DatabaseType.OPENGAUSS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.mysql");
        // v1.9.0: LogMiner 同样按方言家族放行。达梦 / 崖山说 ORACLE 家族，能不能真挖由
        // 连接时的能力探测决定（V$RLOGFILE / ARCH_MODE / RLOG_APPEND_LOGIC，见
        // LogMinerCdcProbe），这里不再按品牌名一票否决。
        assertThat(CaptureStrategyParser.parseRequested("LOGMINER_CDC", DatabaseType.DM))
                .isEqualTo(CaptureStrategy.LOGMINER_CDC);
        assertThat(CaptureStrategyParser.parseRequested("LOGMINER_CDC", DatabaseType.YASHANDB))
                .isEqualTo(CaptureStrategy.LOGMINER_CDC);
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("LOGMINER_CDC", DatabaseType.MYSQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.oracle");
    }

    @Test
    void blankRequestLeavesTheStrategyAlone() {
        assertThat(CaptureStrategyParser.parseRequested("  ", DatabaseType.MYSQL)).isNull();
    }

    // —— v1.9.0 follow-up: CUSTOM connections declare their family ——

    /**
     * A CUSTOM connection is how a PG-compatible product without a preset (IvorySQL,
     * PolarDB-PG, MogDB, …) is added, and the rest of the stack — dialect, type mapping,
     * metadata reader — already follows {@code DatabaseConfig.resolvedFamily()}. The CDC
     * gate must agree, or the one engine that verifies itself by capability stays closed
     * to exactly the products it was built to accept.
     */
    @Test
    void aCustomConnectionThatDeclaresThePostgresFamilyAcceptsPgLogicalCdc() {
        assertThat(CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.POSTGRES))
                .isEqualTo(CaptureStrategy.PG_LOGICAL_CDC);
    }

    @Test
    void aCustomConnectionWithoutADeclaredFamilyStillRejectsPgLogicalCdc() {
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
    }

    /**
     * Only the engines that verify themselves on the live server accept a declared family.
     * Binlog, PG logical decoding and now LogMiner all do (event-stream probe / slot probe /
     * redo-catalogue probe); SQL Server CDC does not, so it keeps its literal preset gate.
     */
    @Test
    void aDeclaredFamilyUnlocksTheCapabilityProbedEngines() {
        assertThat(CaptureStrategyParser.parseRequested("BINLOG_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.MYSQL))
                .isEqualTo(CaptureStrategy.BINLOG_CDC);
        assertThat(CaptureStrategyParser.parseRequested("LOGMINER_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.ORACLE))
                .isEqualTo(CaptureStrategy.LOGMINER_CDC);
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("SQLSERVER_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.SQLSERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.sqlserver");
    }

    /** The two-arg form keeps deciding by the preset's own family (CUSTOM → GENERIC). */
    @Test
    void theTwoArgFormStillRejectsCustomWithoutADeclaredFamily() {
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC",
                DatabaseType.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
    }

    // —— 批量建任务页「按源库自动匹配采集方式」——

    /**
     * 页面列出的选项永远不能是保存门禁会拒绝的选项 —— 否则用户点了「批量创建」才
     * 知道选错了。反过来允许页面收窄（例如崖山实测挖不了日志，就不要把 LogMiner
     * 列出来），所以这条只钉单向：listed ⟹ accepted。
     */
    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void everyListedStrategyPassesTheSaveGate(DatabaseType type) {
        for (CaptureStrategy strategy : CaptureStrategyParser.supportedFor(type, type.getFamily())) {
            assertThat(CaptureStrategyParser.parseRequested(strategy.name(), type))
                    .as("%s listed for %s but the save gate refuses it", strategy, type)
                    .isEqualTo(strategy);
        }
    }

    @Test
    void pollingAndFullCompareComeFirstAndEverySourceHasThem() {
        // 崖山：实测没有 DBMS_LOGMNR / V$LOGMNR_CONTENTS，日志 CDC 不可用 —— 即使它
        // 说 ORACLE 家族、保存门禁放行，页面也不该把 LogMiner 当成可选项。
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.YASHANDB, DatabaseType.YASHANDB.getFamily()))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE);
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.MYSQL, DatabaseType.MYSQL.getFamily()))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE,
                        CaptureStrategy.BINLOG_CDC);
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.ORACLE, DatabaseType.ORACLE.getFamily()))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE,
                        CaptureStrategy.LOGMINER_CDC);
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.DM, DatabaseType.DM.getFamily()))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE,
                        CaptureStrategy.LOGMINER_CDC);
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.SQLSERVER, DatabaseType.SQLSERVER.getFamily()))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE,
                        CaptureStrategy.SQLSERVER_CDC);
    }

    /** 源连接类型解析不出来时，页面只能给出与类型无关的两种。 */
    @Test
    void anUnknownSourceOffersOnlyTheTypeAgnosticStrategies() {
        assertThat(CaptureStrategyParser.supportedFor(null, null))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE);
    }

    /** CUSTOM 连接按它声明的家族算，与保存门禁一致。 */
    @Test
    void aCustomSourceUsesItsDeclaredFamily() {
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.CUSTOM,
                DatabaseType.DialectFamily.POSTGRES))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE,
                        CaptureStrategy.PG_LOGICAL_CDC);
        assertThat(CaptureStrategyParser.supportedFor(DatabaseType.CUSTOM,
                DatabaseType.DialectFamily.SQLSERVER))
                .containsExactly(CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE);
    }
}
