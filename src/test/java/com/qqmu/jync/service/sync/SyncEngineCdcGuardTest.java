package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.GenericMetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * CDC 项目的轮询周期不得动数据:数据阶段只允许发生在基线跑(cdcBaselineRun=true)。
 * 否则 Quartz 周期与流式回放双写同一目标表,DELETE 语义直接冲突(B6/B8)。
 *
 * <p>Harness: a real {@link SyncEngine} driving a real data path — real
 * {@link GenericMetadataReader}s over two H2 databases, the real
 * {@link CursorStrategyResolver} and the real {@link DataSyncService}, so a row actually
 * moves (or does not) and the assertion is on the physical target table. Only the
 * bookkeeping and structure-phase collaborators are mocked: {@link SyncStateWriter}
 * (progress rows / audit log would need Spring Data), {@link StructureSyncService}
 * (pass-through dependency sort; the target table already exists physically) and
 * {@link ChangeDetector} (never reached — structure sync is off in this config).
 */
class SyncEngineCdcGuardTest {

    private static final String SOURCE_URL = "jdbc:h2:mem:cdc_guard_src;DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL = "jdbc:h2:mem:cdc_guard_tgt;DB_CLOSE_DELAY=-1";

    private Connection source;
    private Connection target;

    private DataSourceManager dataSourceManager;
    private StructureSyncService structureSync;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;

    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private Project project;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection(SOURCE_URL, "sa", "");
        target = DriverManager.getConnection(TARGET_URL, "sa", "");
        for (Connection c : List.of(source, target)) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS t (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            }
        }
        try (Statement st = source.createStatement()) {
            st.execute("MERGE INTO t (id, name) VALUES (1, 'a')");
        }

        SyncProperties properties = new SyncProperties();
        dataSourceManager = mock(DataSourceManager.class);
        structureSync = mock(StructureSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        engine = new SyncEngine(dataSourceManager, mock(ChangeDetector.class),
                new CursorStrategyResolver(properties), structureSync,
                new DataSyncService(properties), stateWriter, properties);

        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(source);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(target);
        when(structureSync.sortByDependency(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stateWriter.loadOrCreateProgress(anyLong(), anyString()))
                .thenReturn(new SyncProgress());

        project = new Project();
        project.setId(7L);
        project.setName("cdc-guard");
    }

    @AfterEach
    void tearDown() throws SQLException {
        // runCycle closes the handles it borrowed (and tearDown of a failed test may find
        // them closed already), so clean up through fresh connections: DB_CLOSE_DELAY=-1
        // keeps the in-memory databases alive across the swap.
        for (String url : List.of(SOURCE_URL, TARGET_URL)) {
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
        }
    }

    private SyncContext ctx(CaptureStrategy strategy, boolean baselineRun) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        // H2 folds unquoted identifiers to upper case, so the metadata reader reports "T"
        // and the selection has to match that spelling.
        config.getTables().add("T");
        // Data-phase only: the guard under test lives in syncData, and keeping the
        // structure phase off means the cycle cannot write through any other door.
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        config.setDisableTargetConstraints(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceReader(new GenericMetadataReader())
                .targetReader(new GenericMetadataReader())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .cdcBaselineRun(baselineRun)
                .build();
    }

    private int targetCount() throws SQLException {
        // Fresh handle on purpose: the cycle closed the setUp connection it borrowed.
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void cdcProjectSkipsDataPhaseInNormalCycles() throws SQLException {
        // given: 源表 1 行、目标空; when: 常规轮询周期(非基线)
        SyncResult result = engine.runCycle(ctx(CaptureStrategy.BINLOG_CDC, false));

        // then: 数据阶段被守卫跳过,目标一行未动,周期本身不算失败
        assertThat(result.isSuccess()).isTrue();
        assertThat(targetCount()).isZero();
    }

    @Test
    void cdcBaselineRunAppliesData() throws SQLException {
        // when: 基线全量跑(cdcBaselineRun=true)
        SyncResult result = engine.runCycle(ctx(CaptureStrategy.BINLOG_CDC, true));

        // then: 守卫放行,源表那 1 行真的落到目标
        assertThat(result.isSuccess()).isTrue();
        assertThat(targetCount()).isEqualTo(1);
    }

    @Test
    void pollingStrategyUnaffected() throws SQLException {
        // when: 存量 POLLING 项目的常规周期
        SyncResult result = engine.runCycle(ctx(CaptureStrategy.POLLING, false));

        // then: 行为零变化,数据照常同步
        assertThat(result.isSuccess()).isTrue();
        assertThat(targetCount()).isEqualTo(1);
    }
}
