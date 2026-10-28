package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * probeProject 三用例(任务书 Task 16 Step 1)+ rebaseline 两用例(Task 17 追加)
 * + 跨节点 holder 门三用例(终审 FIX 1 追加)。
 *
 * <p>构造一次到位:除探测五件外还吃 CdcConsumerManager/CdcPositionStore/SyncStateWriter/
 * SyncTaskStore/AdminAuditService/ChangeLogRepository/SyncLockService,全是 Task 17
 * rebaseline 及其 holder 门的依赖。源库 stub 照抄 CdcBaselineServiceTest 的做法(mock
 * Connection + DatabaseConfig 实体直填)。
 */
@ExtendWith(MockitoExtension.class)
class CdcOperationsServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private SyncContextFactory contextFactory;
    @Mock private BinlogCdcProbe probe;
    @Mock private LogMinerCdcProbe logMinerProbe;
    @Mock private PgLogicalReplicationCdcProbe pgProbe;
    @Mock private SqlServerCdcProbe mssqlProbe;
    @Mock private BinlogClientFactory clientFactory;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private CdcPositionStore positionStore;
    @Mock private SyncStateWriter stateWriter;
    @Mock private SyncTaskStore taskStore;
    @Mock private AdminAuditService auditService;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private SyncLockService lockService;

    private CdcOperationsService service;

    @BeforeEach
    void setUp() {
        service = new CdcOperationsService(projectRepository, databaseConfigRepository,
                dataSourceManager, contextFactory, probe, logMinerProbe, pgProbe, mssqlProbe,
                clientFactory, cdcManager, positionStore, stateWriter, taskStore,
                auditService, changeLogRepository, lockService);
    }

    private Project project() {
        Project p = new Project();
        p.setId(1L);
        p.setName("proj");
        p.setSourceDbId(5L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        return p;
    }

    private DatabaseConfig source(DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(5L);
        config.setType(type);
        config.setHost("db.local");
        config.setPort(3306);
        config.setUsername("repl");
        when(databaseConfigRepository.findById(5L)).thenReturn(Optional.of(config));
        return config;
    }

    /** 5 过 2 败(SERVER_ID 冲突 + 权限缺失),探测逐项返回、总判定为不可用。 */
    private static CdcProbeResult fivePassTwoFail() {
        return CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.VERSION, true, "MySQL 8.0.36"),
                CdcProbeResult.Check.of(CdcProbeResult.LOG_BIN, true, "ON"),
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_FORMAT, true, "ROW"),
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_ROW_IMAGE, true, "FULL"),
                CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, false,
                        "source server_id 612001 collides with the id Jync would present (612001)"
                                + " — change sync.cdc.server-id-offset"),
                CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, false,
                        "missing grant(s): REPLICATION SLAVE"),
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, true,
                        "binlog_expire_logs_seconds=604800")));
    }

    @Test
    void probeProjectReturnsItemizedChecks() throws SQLException {
        project();
        DatabaseConfig source = source(DatabaseType.MYSQL);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        when(clientFactory.serverIdFor(1L)).thenReturn(612001L);
        when(probe.probe(any(Connection.class), any(DatabaseConfig.class), anyLong()))
                .thenReturn(fivePassTwoFail());

        CdcProbeResult result = service.probeProject(1L);

        assertThat(result.passed()).isFalse();
        assertThat(result.getChecks()).hasSize(7);
        // serverIdConflict 由 SERVER_ID 项推导:该项失败且 detail 指出与计划 id 相撞。
        CdcProbeResult.Check serverId = result.getChecks().stream()
                .filter(c -> CdcProbeResult.SERVER_ID.equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(serverId.isPassed()).isFalse();
        assertThat(serverId.getDetail()).contains("612001");
        // 探测收到的 plannedServerId 必须就是 serverIdFor(projectId)。
        ArgumentCaptor<Long> plannedServerId = ArgumentCaptor.forClass(Long.class);
        verify(probe).probe(same(conn), same(source), plannedServerId.capture());
        assertThat(plannedServerId.getValue()).isEqualTo(612001L);
        assertThat(plannedServerId.getValue()).isEqualTo(clientFactory.serverIdFor(1L));
        assertThat(source.getType()).isEqualTo(DatabaseType.MYSQL);
    }

    @Test
    void probeProjectRunsThePgProbeForEveryPostgresFamilySource() throws SQLException {
        // v1.9.0:PG 家族成员（openGauss 等）不再被 cdc.probe.unsupported 拒绝，
        // 统一走 PG 逻辑复制探测；能否使用由探测结果决定。
        project();
        source(DatabaseType.OPENGAUSS);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        CdcProbeResult expected = CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.PG_SOURCE, true, "openGauss 5.0.0")));
        when(pgProbe.probe(any(Connection.class),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(expected);

        assertThat(service.probeProject(1L)).isSameAs(expected);
    }

    /**
     * 项目级探测必须把手上的表清单交给探测:表级检查(REPLICA IDENTITY)以前永远收不到表,
     * 于是对真实项目从不执行——探测报「全部通过」而某张表的 UPDATE/DELETE 事件连旧键都
     * 不会带出来,消费端静默漏数。schema 取连接配置,未配置时问连接本身。
     */
    @Test
    void probeProjectHandsTheProjectsTablesToThePgProbe() throws SQLException {
        Project p = project();
        p.setSyncConfig("{\"captureStrategy\":\"PG_LOGICAL_CDC\",\"objectSelectionExplicit\":true,"
                + "\"tables\":[\"t1\",\"sales.t2\"]}");
        SyncConfig config = new SyncConfig();
        config.getTables().addAll(List.of("t1", "sales.t2"));
        when(contextFactory.parseConfig(p)).thenReturn(config);
        DatabaseConfig src = source(DatabaseType.OPENGAUSS);
        src.setSchemaName("app");
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        when(pgProbe.probe(any(Connection.class), anyList()))
                .thenReturn(CdcProbeResult.of(List.of()));

        service.probeProject(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String[]>> tables = ArgumentCaptor.forClass(List.class);
        verify(pgProbe).probe(same(conn), tables.capture());
        assertThat(tables.getValue().stream().map(t -> t[0] + "." + t[1]).toList())
                .containsExactly("app.t1", "sales.t2");
    }

    /** 未显式选表(旧配置 = 同步源库全部对象)时清单未知,跳过表级检查而不是当成「没有表」。 */
    @Test
    void probeProjectSkipsTableChecksWhenTheSelectionIsImplicit() throws SQLException {
        Project p = project();
        p.setSyncConfig("{\"captureStrategy\":\"PG_LOGICAL_CDC\"}");
        when(contextFactory.parseConfig(p)).thenReturn(new SyncConfig()); // 空表集 = 源库全部
        source(DatabaseType.POSTGRESQL);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        when(pgProbe.probe(any(Connection.class), anyList()))
                .thenReturn(CdcProbeResult.of(List.of()));

        service.probeProject(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String[]>> tables = ArgumentCaptor.forClass(List.class);
        verify(pgProbe).probe(same(conn), tables.capture());
        assertThat(tables.getValue()).isEmpty();
    }

    /**
     * A CUSTOM connection that declares the PostgreSQL family is a PostgreSQL server as far
     * as the rest of the stack is concerned ({@code DatabaseConfig.resolvedFamily}).
     * The probe must dispatch to the PG checks — which decide usability by capability —
     * instead of rejecting the connection as "unsupported" before it ever connects.
     */
    @Test
    void probeProjectDispatchesACustomConnectionWithThePostgresFamilyToThePgProbe()
            throws SQLException {
        Project p = project();
        p.setSyncConfig("{\"captureStrategy\":\"PG_LOGICAL_CDC\"}");
        when(contextFactory.parseConfig(p)).thenReturn(new SyncConfig());
        DatabaseConfig src = source(DatabaseType.CUSTOM);
        src.setDialectFamily(DatabaseType.DialectFamily.POSTGRES);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        when(pgProbe.probe(any(Connection.class), anyList()))
                .thenReturn(CdcProbeResult.of(List.of()));

        service.probeProject(1L);

        verify(pgProbe).probe(same(conn), anyList());
        verify(logMinerProbe, never()).probe(any(), anyList(), any());
    }

    @Test
    void probeProjectDispatchesAMariaDbSourceToTheBinlogProbe() throws SQLException {
        project();
        DatabaseConfig src = source(DatabaseType.MARIADB);
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        when(probe.probe(any(Connection.class), any(DatabaseConfig.class), anyLong()))
                .thenReturn(CdcProbeResult.of(List.of()));

        service.probeProject(1L);

        verify(probe).probe(same(conn), same(src), anyLong());
        verify(pgProbe, never()).probe(any(), anyList());
        verify(logMinerProbe, never()).probe(any(), anyList(), any());
    }

    @Test
    void probeProjectRejectsUnsupportedSource() throws SQLException {
        project();
        source(DatabaseType.DB2);   // DB2 has no CDC integration yet — must be rejected

        assertThatThrownBy(() -> service.probeProject(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cdc.probe.unsupported");
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void probeProjectSwallowsConnectionFailure() throws SQLException {
        project();
        source(DatabaseType.MYSQL);
        when(dataSourceManager.getConnection(any(DatabaseConfig.class)))
                .thenThrow(new SQLException("connection refused by db.local:3306"));

        // 与 ConnectionTestService.test 永不抛的既有约定一致:探测失败是结果,不是异常。
        CdcProbeResult[] result = new CdcProbeResult[1];
        assertThatCode(() -> result[0] = service.probeProject(1L)).doesNotThrowAnyException();

        assertThat(result[0].passed()).isFalse();
        CdcProbeResult.Check version = result[0].getChecks().stream()
                .filter(c -> CdcProbeResult.VERSION.equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(version.isPassed()).isFalse();
        assertThat(version.getDetail()).contains("connection refused by db.local:3306");
    }

    /**
     * A CDC-strategy row; two instances let the sequence test tell the fresh re-read
     * apart. Enabled by default: the restart sequence is the running-task path.
     */
    private static Project cdcProject(String name) {
        Project p = new Project();
        p.setId(1L);
        p.setName(name);
        p.setEnabled(true);
        p.setSyncConfig("{\"captureStrategy\":\"BINLOG_CDC\"}");
        return p;
    }

    /** The same row with the task stopped: rebaseline resets state but must not start. */
    private static Project stoppedCdcProject(String name) {
        Project p = cdcProject(name);
        p.setEnabled(false);
        return p;
    }

    /**
     * Task 17 用例 4:rebaseline 五连严格按序执行(T15 purge IT 实测过的顺序,不可重排),
     * 且 ensureStarted 拿到的是清空状态后【重新读取】的行;双 trail 落 ChangeLog(INFO
     * "baseline rebuilt")+ 审计 PROJECT_CDC_REBASELINE。markCdcRunning 摘 ERROR 帽子
     * 必须在 ensureStarted 之前——致命 purge 停机后不摘帽子,ensureStarted 会静默跳过。
     */
    @Test
    void rebaselineRunsSequenceAndAudits() {
        Project stale = cdcProject("stale");
        Project fresh = cdcProject("fresh");
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(stale), Optional.of(fresh));

        assertThat(service.rebaseline(1L)).isTrue();

        InOrder sequence = inOrder(cdcManager, positionStore, stateWriter, taskStore);
        sequence.verify(cdcManager).ensureStopped(1L);
        sequence.verify(positionStore).delete(1L);
        sequence.verify(stateWriter).clearCursor(1L);
        sequence.verify(taskStore).markCdcRunning(1L);
        sequence.verify(cdcManager).ensureStarted(same(fresh));

        ArgumentCaptor<ChangeLog> changeLog = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogRepository).save(changeLog.capture());
        assertThat(changeLog.getValue().getProjectId()).isEqualTo(1L);
        assertThat(changeLog.getValue().getObjectType()).isEqualTo(ObjectType.PROJECT);
        assertThat(changeLog.getValue().getChangeType()).isEqualTo(ChangeType.INFO);
        assertThat(changeLog.getValue().getDetails()).contains("baseline rebuilt");
        verify(auditService).record(eq(AuditAction.PROJECT_CDC_REBASELINE), anyString());
    }

    /**
     * 停用任务上的重建基线只重置状态:清位点、清游标、摘 ERROR 帽(markStopped),
     * 不启动消费者。起点消费者会让巡检在 join 20 秒后打断全量,页面上还会同时出现
     * 「已停止」和「CDC 运行中 + 锁持有中」。基线留给启用后的 ensureStarted 执行
     * (无位点 → 全量)。双 trail 仍然要写:用户确实执行了一次重置。
     */
    @Test
    void rebaselineOnDisabledProjectClearsBaselineButDoesNotStart() {
        Project stale = stoppedCdcProject("stale");
        Project fresh = stoppedCdcProject("fresh");
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(stale), Optional.of(fresh));

        assertThat(service.rebaseline(1L)).isFalse();

        InOrder sequence = inOrder(cdcManager, positionStore, stateWriter, taskStore);
        sequence.verify(cdcManager).ensureStopped(1L);
        sequence.verify(positionStore).delete(1L);
        sequence.verify(stateWriter).clearCursor(1L);
        sequence.verify(taskStore).markStoppedClearingResult(1L); // 摘帽、清旧错误文本,不置 RUNNING
        verify(taskStore, never()).markCdcRunning(1L);
        verify(cdcManager, never()).ensureStarted(any());

        ArgumentCaptor<ChangeLog> changeLog = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogRepository).save(changeLog.capture());
        assertThat(changeLog.getValue().getProjectId()).isEqualTo(1L);
        assertThat(changeLog.getValue().getChangeType()).isEqualTo(ChangeType.INFO);
        assertThat(changeLog.getValue().getDetails()).contains("baseline reset");
        verify(auditService).record(eq(AuditAction.PROJECT_CDC_REBASELINE), anyString());
    }

    /** Task 17 用例 5:非 CDC 项目 → IllegalArgumentException,manager 零交互。 */
    @Test
    void rebaselineRejectsPollingProject() {
        Project polling = new Project();
        polling.setId(1L);
        polling.setName("proj");
        polling.setSyncConfig(null);   // legacy row: no strategy = POLLING
        when(projectRepository.findById(1L)).thenReturn(Optional.of(polling));

        assertThatThrownBy(() -> service.rebaseline(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.cdc");
        verifyNoInteractions(cdcManager, positionStore, stateWriter, taskStore,
                auditService, changeLogRepository);
    }

    // —— 终审 FIX 1:跨节点 holder 门。非持有者上 ensureStopped/ensureStarted 都是空转,
    // positionStore.delete 却会杀掉持有者的活位点行,API 还回 ok:true + 双 trail —— 假成功。
    // 门必须在任何变更之前拒绝,且一行 trail 都不许写。——

    /** FIX 1 用例 ①:本节点无消费者(statsFor null)且租约被另一 LIVE 节点持有 → 拒绝。 */
    @Test
    void rebaselineRefusedWhileLeaseHeldByAnotherLiveNode() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(cdcProject("proj")));
        when(cdcManager.statsFor(1L)).thenReturn(null);
        when(lockService.isLeaseHeldByAnotherLiveOwner(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.rebaseline(1L))
                .isInstanceOf(CdcNotHolderException.class)
                .hasMessage("cdc.rebaseline.not.holder");

        // 零变更:五连一步都没跑,持有者的位点行完好
        verify(cdcManager, never()).ensureStopped(anyLong());
        verify(cdcManager, never()).ensureStarted(any());
        verify(positionStore, never()).delete(anyLong());
        // 零 trail:假成功的 ChangeLog「baseline rebuilt」与审计绝不落库
        verifyNoInteractions(stateWriter, taskStore, auditService, changeLogRepository);
    }

    /**
     * FIX 1 用例 ②:本节点就是持有者(注册表有消费者)→ 门放行,五连照常执行;
     * statsFor 非空即短路,租约查询根本不该发生(本地注册优先于租约行的任何说法)。
     */
    @Test
    void rebaselineProceedsOnTheHolderNode() {
        Project stale = cdcProject("stale");
        Project fresh = cdcProject("fresh");
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(stale), Optional.of(fresh));
        when(cdcManager.statsFor(1L)).thenReturn(new CdcRuntimeStats());

        service.rebaseline(1L);

        verify(cdcManager).ensureStopped(1L);
        verify(positionStore).delete(1L);
        verify(cdcManager).ensureStarted(same(fresh));
        verifyNoInteractions(lockService);
    }

    /**
     * FIX 1 用例 ③:无本地消费者但外来租约已失效/已释放(致命 park 的持有者在 finally
     * 里 lock.close(),或死节点的租约过了 TTL)→ 恢复路径必须放行:T15 purge IT 的服务层
     * 序列(delete → markCdcRunning → ensureStarted)就依赖它能在本节点执行。
     */
    @Test
    void rebaselineProceedsWhenForeignLeaseIsNotLive() {
        Project stale = cdcProject("stale");
        Project fresh = cdcProject("fresh");
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(stale), Optional.of(fresh));
        when(cdcManager.statsFor(1L)).thenReturn(null);
        when(lockService.isLeaseHeldByAnotherLiveOwner(1L)).thenReturn(false);

        service.rebaseline(1L);

        verify(positionStore).delete(1L);
        verify(taskStore).markCdcRunning(1L);
        verify(cdcManager).ensureStarted(same(fresh));
        verify(auditService).record(eq(AuditAction.PROJECT_CDC_REBASELINE), anyString());
    }
}
