package com.qqmu.jync.it;


import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * v1.6.0 Oracle LogMiner 基线集成测试 — PDB-local 源连接。
 *
 * <p>证明：LOGMINER_CDC 策略下基线全量加载、SCN 位点记录、baselineDone 标记。
 * 流式挖掘由 LogMinerPathSpikeIT 单独实证（CDB 模式需 root 连接，生产代码将在
 * 后续版本支持分离的 mining connection）。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it — run explicitly with
 * {@code mvn test -Dtest=LogMinerBaselineStreamingIT -Dapi.version=1.44}.
 */
class LogMinerBaselineStreamingIT extends OracleCdcHarnessIT {

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @Test
    void baselineLoadsAndRecordsScn() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE lm_basic (id NUMBER(19) PRIMARY KEY, name VARCHAR2(64))");
            st.execute("ALTER TABLE lm_basic ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            for (int i = 1; i <= 100; i++) {
                st.execute("INSERT INTO lm_basic VALUES (" + i + ", 'seed-" + i + "')");
            }
        }
        // 目标表由 StructureSyncService 自动创建（Oracle 不支持 IF NOT EXISTS，手动建会 ORA-00955）

        Project project = newProject("lm-basic", logMinerConfig("LM_BASIC"));
        manager.ensureStarted(project);

        // 基线完成：baselineDone=true 且 scn>0（LogMiner 特有位点）
        waitFor("baseline done", 120_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone() && p.getScn() != null && p.getScn() > 0)
                .orElse(false));
        waitFor("100 seeded rows", 60_000, () -> countTarget("LM_BASIC") == 100);

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    private SyncConfig logMinerConfig(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.LOGMINER_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }
}
