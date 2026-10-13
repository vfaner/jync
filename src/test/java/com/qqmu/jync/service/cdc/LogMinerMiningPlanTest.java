package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/**
 * P0-2: the mining-plan decision is the one place CDB mode is chosen. Every branch is
 * pinned: wrong choice either dies with ORA-65040 at first mine (PDB without miner
 * credentials) or silently captures nothing (root connection treated as a PDB).
 */
class LogMinerMiningPlanTest {

    @Test
    void nonCdbMinesOnTheSourceConnection() throws Exception {
        assertThat(LogMinerCdcConsumer.decideMiningPlan(Boolean.FALSE, 0, false))
                .isEqualTo(LogMinerCdcConsumer.MiningPlan.SOURCE);
    }

    @Test
    void indeterminateCdbFlagFallsBackToSourceMining() throws Exception {
        // V$DATABASE.CDB 不可读 = 11g(该列 12c 才有)→ 必非 CDB。12c+ 授权清单含
        // V_$DATABASE,读不到是配置事故,但按 SOURCE 走下去第一轮挖掘就会以真错露面,
        // 比在这里猜更诚实。
        assertThat(LogMinerCdcConsumer.decideMiningPlan(null, 0, false))
                .isEqualTo(LogMinerCdcConsumer.MiningPlan.SOURCE);
    }

    @Test
    void rootConnectionKeepsLegacyRootOnlyCapture() throws Exception {
        // 直连 CDB$ROOT 的旧行为:只挖 root 容器对象(SRC_CON_ID=1 的行)。有人可能
        // 就在同步 root 里的表——不fail,但消费端会 WARN 一次说明捕获范围。
        assertThat(LogMinerCdcConsumer.decideMiningPlan(Boolean.TRUE, 1, false))
                .isEqualTo(LogMinerCdcConsumer.MiningPlan.SOURCE);
        assertThat(LogMinerCdcConsumer.decideMiningPlan(Boolean.TRUE, 1, true))
                .isEqualTo(LogMinerCdcConsumer.MiningPlan.SOURCE);
    }

    @Test
    void pdbConnectionWithMinerCredentialsMinesViaRoot() throws Exception {
        assertThat(LogMinerCdcConsumer.decideMiningPlan(Boolean.TRUE, 3, true))
                .isEqualTo(LogMinerCdcConsumer.MiningPlan.ROOT);
    }

    @Test
    void pdbConnectionWithoutMinerCredentialsFailsFastWithOra65040() {
        // 今日行为:第一轮 START_LOGMNR 吃 ORA-65040,被分类器 park 成 ERROR,文案
        // 不指向解药。fail-fast 在判定处抛出同源错误码 + 指明"配置挖掘账号"。
        assertThatThrownBy(() -> LogMinerCdcConsumer.decideMiningPlan(Boolean.TRUE, 3, false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-65040")
                .hasMessageContaining("miner");
    }
}
