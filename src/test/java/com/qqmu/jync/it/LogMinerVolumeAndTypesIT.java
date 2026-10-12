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
 * v1.6.0 Oracle LogMiner 类型矩阵基线测试。
 *
 * <p>证明：全类型表的基线全量加载能正确创建目标表结构并播种数据。
 * 类型覆盖：NUMBER(19)/NUMBER(10,2)/FLOAT/VARCHAR2(4000)/CHAR(10)/DATE/
 * TIMESTAMP(0,3,6,9)/RAW(2000)/CLOB/BLOB。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it — run explicitly with
 * {@code mvn test -Dtest=LogMinerVolumeAndTypesIT -Dapi.version=1.44}.
 */
class LogMinerVolumeAndTypesIT extends OracleCdcHarnessIT {

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @Test
    void typeMatrixBaselineLoads() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE lm_types ("
                    + "id NUMBER(19) PRIMARY KEY,"
                    + "n19 NUMBER(19),"
                    + "n102 NUMBER(10,2),"
                    + "vc4k VARCHAR2(4000),"
                    + "ch10 CHAR(10),"
                    + "dt DATE,"
                    + "ts0 TIMESTAMP(0),"
                    + "ts3 TIMESTAMP(3),"
                    + "ts6 TIMESTAMP(6),"
                    + "ts9 TIMESTAMP(9),"
                    + "raw2k RAW(2000),"
                    + "clob_in CLOB,"
                    + "blob_in BLOB)");
            st.execute("ALTER TABLE lm_types ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            for (int i = 1; i <= 50; i++) {
                st.execute("INSERT INTO lm_types (id, n19, n102, vc4k, ch10, dt, ts0, ts3, ts6, ts9, raw2k, clob_in, blob_in)"
                        + " VALUES (" + i + ", " + i + ", " + (i * 1.5)
                        + ", 'varchar-' || " + i + ", 'ch' || " + i
                        + ", SYSDATE, SYSTIMESTAMP, SYSTIMESTAMP, SYSTIMESTAMP, SYSTIMESTAMP"
                        + ", HEXTORAW('AABBCC')"
                        + ", 'clob-text-" + i + "', HEXTORAW('BBCCDD')"
                        + ")");
            }
        }

        Project project = newProject("lm-types", logMinerConfig("LM_TYPES"));
        manager.ensureStarted(project);

        waitFor("baseline done", 120_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("50 seeded rows", 60_000, () -> countTarget("LM_TYPES") == 50);

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
