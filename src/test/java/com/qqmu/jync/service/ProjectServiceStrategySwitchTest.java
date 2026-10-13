package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * C2 切换全流程。搭法照抄 ProjectServiceTest(@ExtendWith(MockitoExtension) + 全 @Mock);
 * 事务 synchronization 不活跃时 afterCommit 副作用同步执行(对照既有
 * aValidCronExpressionIsAccepted 直接 verify(scheduler) 的先例),测试无需手工触发。
 *
 * <p>与任务书草案的两处机械偏差(现实优先):①ProjectService 构造在 Task 13 后已是 16 参
 * (CdcConsumerManager 已注入),本任务只追加 stateWriter/positionStore 两个,共 18 参;
 * ②switchToCdcOnNonMysqlRejected 在任何序列化/落库之前就抛出,helper 里
 * serializeConfig/save 两条桩对该用例不可达,STRICT_STUBS 下须 lenient()。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceStrategySwitchTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private com.qqmu.jync.repository.ChunkCheckpointRepository checkpointRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;
    @Mock private SyncLockService lockService;
    @Mock private AdminAuditService auditService;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private CdcPositionStore positionStore;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                new com.qqmu.jync.config.SyncProperties());
    }

    private Project mysqlProject(String currentStrategyJson) {
        Project p = new Project();
        p.setId(1L);
        p.setName("proj");
        p.setSourceDbId(5L);
        p.setEnabled(true);
        p.setSyncConfig(currentStrategyJson);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        DatabaseConfig source = new DatabaseConfig();
        source.setType(DatabaseType.MYSQL);
        when(databaseConfigRepository.findById(5L)).thenReturn(Optional.of(source));
        // lenient:非 MySQL 拒绝用例在序列化与落库之前就抛出,这两条桩对它不可达。
        lenient().when(contextFactory.serializeConfig(any())).thenReturn("{\"captureStrategy\":\"NEW\"}");
        lenient().when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return p;
    }

    @Test
    void switchToCdcOnNonMysqlRejected() {
        mysqlProject("{\"captureStrategy\":\"POLLING\"}");
        DatabaseConfig pg = new DatabaseConfig();
        pg.setType(DatabaseType.POSTGRESQL);
        when(databaseConfigRepository.findById(5L)).thenReturn(Optional.of(pg));

        assertThatThrownBy(() -> service.saveConfig(1L, new SyncConfig(), "BINLOG_CDC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.mysql");
        verify(projectRepository, never()).save(any());   // 配置不落库
        verify(cdcManager, never()).ensureStarted(any());
    }

    @Test
    void switchPollingToCdcRunsFullC2Sequence() {
        mysqlProject("{\"captureStrategy\":\"POLLING\"}");

        service.saveConfig(1L, new SyncConfig(), "BINLOG_CDC");

        InOrder order = inOrder(scheduler, stateWriter, positionStore, taskStore, cdcManager);
        order.verify(scheduler).unschedule(1L);
        order.verify(stateWriter).clearCursor(1L);
        order.verify(positionStore).delete(1L);
        order.verify(taskStore).markCdcRunning(1L);       // 摘旧 ERROR 帽子
        order.verify(cdcManager).ensureStarted(any());
        verify(auditService).record(eq(AuditAction.PROJECT_CAPTURE_SWITCH),
                contains("POLLING -> BINLOG_CDC"));
    }

    @Test
    void switchCdcToPollingStopsConsumerAndKeepsPosition() {
        mysqlProject("{\"captureStrategy\":\"BINLOG_CDC\"}");

        service.saveConfig(1L, new SyncConfig(), "POLLING");

        InOrder order = inOrder(cdcManager, stateWriter, scheduler);
        order.verify(cdcManager).ensureStopped(1L);       // 先停流(同步 join)
        order.verify(stateWriter).clearCursor(1L);        // 游标重新解析
        order.verify(scheduler).reschedule(any());
        verify(positionStore, never()).delete(anyLong()); // C2:CDC→轮询保留位点
        verify(auditService).record(eq(AuditAction.PROJECT_CAPTURE_SWITCH),
                contains("BINLOG_CDC -> POLLING"));
    }

    @Test
    void sameStrategySaveDoesNotTriggerSwitchSideEffects() {
        mysqlProject("{\"captureStrategy\":\"POLLING\"}");

        service.saveConfig(1L, new SyncConfig(), "POLLING");

        verify(cdcManager, never()).ensureStarted(any());
        verify(cdcManager, never()).ensureStopped(anyLong());
        verify(positionStore, never()).delete(anyLong());
        verify(stateWriter, never()).clearCursor(anyLong());
        verify(scheduler).reschedule(any());              // 既有保存路径不变
    }

    @Test
    void unknownJsonFieldDoesNotFakeStrategySwitch() {
        // 钉住 parseStrategy 与 CdcConsumerManager#strategyOf 对“未知字段”的端到端一致:
        // 未来版本写下的行(或手工改过的行)带未知字段时,两边都必须读出 BINLOG_CDC——
        // 否则一次“未改策略”的保存会被误判为 POLLING→BINLOG_CDC 切换,误发整个前向
        // C2 序列(删位点、重基线、假审计)。宽容度现有双保险(SyncConfig 带
        // @JsonIgnoreProperties(ignoreUnknown=true);两处 mapper 又都关了
        // FAIL_ON_UNKNOWN_PROPERTIES),本用例防止任何一层将来退化。
        mysqlProject("{\"captureStrategy\":\"BINLOG_CDC\",\"futureFlag\":true}");

        service.saveConfig(1L, new SyncConfig(), "BINLOG_CDC");

        verify(positionStore, never()).delete(anyLong());
        verify(taskStore, never()).markCdcRunning(anyLong());
        verify(cdcManager, never()).ensureStarted(any());
        verify(auditService, never()).record(eq(AuditAction.PROJECT_CAPTURE_SWITCH), any());
        verify(scheduler).reschedule(any());              // 同策略:只走既有重排路径
    }
}
