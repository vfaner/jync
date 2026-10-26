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
 * v1.6.0 Oracle LogMiner 韧性测试。
 *
 * <p>证明：伪造残缺位点（baseline_done=false + scn>0）→ 消费者整体重跑基线 → 恢复正常。
 * 这是 Review Focus #4（基线中途崩溃整体重跑）的 Oracle 版本。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it — run explicitly with
 * {@code mvn test -Dtest=LogMinerResilienceIT -Dapi.version=1.44}.
 */
class LogMinerResilienceIT extends OracleCdcHarnessIT {

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @Test
    void crashMidBaselineRerunsWholeBaseline() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE lm_resil (id NUMBER(19) PRIMARY KEY, v VARCHAR2(32))");
            st.execute("ALTER TABLE lm_resil ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            st.execute("INSERT INTO lm_resil VALUES (1, 'x')");
        }

        // 伪造「基线中途崩溃」：位点行存在但 baseline_done=false，scn>0
        Project project = newProject("lm-resil", logMinerConfig("LM_RESIL"), false);
        positionStore.recordBaselineStartScn(project.getId(), 100000L);
        project.setEnabled(true);
        projectRepository.save(project);

        manager.ensureStarted(reload(project));
        waitFor("baseline redone", 120_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("lm_resil row present", 60_000, () -> countTarget("LM_RESIL") == 1);
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
