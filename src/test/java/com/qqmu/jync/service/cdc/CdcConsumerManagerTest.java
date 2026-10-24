package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncTaskRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class CdcConsumerManagerTest {

    private ProjectRepository projectRepository;
    private SyncTaskRepository taskRepository;
    private SyncLockService lockService;
    private SyncContextFactory contextFactory;
    private BinlogClientFactory clientFactory;
    private CdcPositionStore positionStore;
    private CdcBaselineService baselineService;
    private SyncTaskStore taskStore;
    private SyncStateWriter stateWriter;
    private DataSourceManager dataSourceManager;
    private CdcConsumerManager manager;
    private SyncLockService.LockHandle lock;
    private RecordingStarter starter;

    /** 真起线程的记录型启动器:返回 Thread 供 manager join(与默认实现同语义)。 */
    static final class RecordingStarter implements CdcConsumerManager.ConsumerStarter {
        Runnable last;
        String lastName;
        Thread lastThread;

        @Override
        public Thread start(Runnable consumer, String threadName) {
            this.last = consumer;
            this.lastName = threadName;
            Thread t = new Thread(consumer, threadName);
            t.setDaemon(true);
            t.start();
            this.lastThread = t;
            return t;
        }
    }

    private Project cdcProject(long id, boolean enabled) {
        return projectWithStrategy(id, enabled, CaptureStrategy.BINLOG_CDC);
    }

    private Project projectWithStrategy(long id, boolean enabled, CaptureStrategy strategy) {
        Project project = new Project();
        project.setId(id);
        project.setEnabled(enabled);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        // config JSON 按 SyncContextFactory.parseConfig 期望的序列化格式塞进 project.setSyncConfig(...)
        // (Project 实体字段名是 syncConfig,已核实)
        project.setSyncConfig(new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(config).toString());
        return project;
    }

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        taskRepository = mock(SyncTaskRepository.class);
        lockService = mock(SyncLockService.class);
        contextFactory = mock(SyncContextFactory.class);
        clientFactory = mock(BinlogClientFactory.class);
        positionStore = mock(CdcPositionStore.class);
        baselineService = mock(CdcBaselineService.class);
        taskStore = mock(SyncTaskStore.class);
        stateWriter = mock(SyncStateWriter.class);
        dataSourceManager = mock(DataSourceManager.class);
        lock = mock(SyncLockService.LockHandle.class);
        when(lock.owner()).thenReturn("host:1:feed");
        // R1(控制器裁定):消费者在重连循环顶部先 renew()——false 即安静退出。测试里
        // 租约必须视为持有,否则消费者在建流之前就走 lease-lost 出口,connect 桩永远等不到。
        when(lock.renew()).thenReturn(true);
        when(contextFactory.build(any(), anyString(), any())).thenReturn(
                SyncContext.builder().config(new SyncConfig()).build());
        starter = new RecordingStarter();
        // SyncProperties 默认值即测试期望值:cdc.lockTtlMs=30_000(Task 1 钉死);
        // taskRepository.findByProjectId 未 stub → Mockito 默认 Optional.empty → 无 ERROR 帽子,不挡启动
        LogMinerSessionFactory sessionFactory = (source, startScn, batchSize) -> {
            throw new UnsupportedOperationException("session factory not exercised here");
        };
        manager = new CdcConsumerManager(projectRepository, taskRepository, lockService,
                contextFactory, clientFactory, positionStore, baselineService, taskStore,
                stateWriter, dataSourceManager, new SyncProperties(), starter, sessionFactory);
    }

    @Test
    void ensureStartedAcquiresDaemonLeaseAndLaunches() {
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        manager.ensureStarted(cdcProject(9L, true));
        assertThat(starter.last).isNotNull();
        assertThat(starter.lastName).isEqualTo("jync-cdc-9");
        verify(lockService).tryAcquireForDaemon(9L, 30_000L);
        manager.ensureStopped(9L);
    }

    @Test
    void ensureStartedSkipsWhenAnotherNodeHoldsLease() {
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(null);
        manager.ensureStarted(cdcProject(9L, true));
        assertThat(starter.last).isNull(); // 不持锁不启动,等 supervise 接管
    }

    @Test
    void ensureStartedIsIdempotent() {
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        manager.ensureStarted(cdcProject(9L, true));
        manager.ensureStarted(cdcProject(9L, true));
        verify(lockService, org.mockito.Mockito.times(1)).tryAcquireForDaemon(anyLong(), anyLong());
        manager.ensureStopped(9L);
    }

    @Test
    void aTransientStartFailureDoesNotParkTheProjectForGood() {
        // 应用与数据库同时启动、数据库慢半拍(或 supervise 某一拍撞上网络抖动)时,
        // SyncContextFactory.build 在解析默认 schema 时抛 SQLException → IllegalStateException。
        // CdcFailureClassifier 判为 TRANSIENT —— 文档明说"退避重试才是补救"。
        // 旧实现无条件 park:项目被永久戴上 ERROR 帽,supervise 在所有节点都不再启动它,
        // CDC 静默死亡,只能人工重建基线。契约:TRANSIENT 不 park、释放租约,下一轮重试。
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        when(contextFactory.build(any(), anyString(), any())).thenThrow(new IllegalStateException(
                "Cannot connect to 'src' to determine its schema: Connection refused",
                new java.sql.SQLException("Connection refused")));

        assertThatThrownBy(() -> manager.ensureStarted(cdcProject(9L, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Connection refused");

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(com.qqmu.jync.model.ChangeType.ERROR), anyString(), anyBoolean(), anyLong());
        verify(lock).close();                                    // 租约必须放回去,他节点可接管
    }

    @Test
    void aFatalStartFailureStillParksTheProject() {
        // 对照:人必须介入的失败(凭据错误)仍按既有 B7 语义永久 park —— 本轮修复只放过
        // TRANSIENT,不能把"防跨节点重启风暴"的帽子一起摘掉。
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        when(contextFactory.build(any(), anyString(), any())).thenThrow(new IllegalStateException(
                "Cannot connect to 'src': Access denied for user 'jync'",
                new java.sql.SQLException("Access denied for user 'jync'")));

        assertThatThrownBy(() -> manager.ensureStarted(cdcProject(9L, true)))
                .isInstanceOf(IllegalStateException.class);

        verify(taskStore).recordCdcFailure(eq(9L), anyString());
        verify(lock).close();
    }

    @Test
    void aParkedProjectWarnsOnceAcrossSuperviseTicks() {
        // supervise 每 10s 重新问一遍同一个被 ERROR 帽挡住的项目。每拍都 WARN,日志就成了
        // 同一条消息的复读机(现场一天几万行);警告的语义是"状态变成被 park",一段停机
        // 只该出现一次,真正的复述交给 DEBUG。
        Project project = cdcProject(9L, true);
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project));
        when(taskRepository.findByProjectId(9L)).thenReturn(Optional.of(parkedTask(9L)));

        ListAppender<ILoggingEvent> logs = LogCapture.attach(CdcConsumerManager.class);
        try {
            manager.supervise();
            manager.supervise();
            manager.supervise();
            assertThat(LogCapture.messagesAt(Level.WARN, logs))
                    .as("同一段 ERROR 停机只该警告一次")
                    .hasSize(1);
        } finally {
            LogCapture.detach(CdcConsumerManager.class, logs);
        }
        verify(lockService, never()).tryAcquireForDaemon(anyLong(), anyLong());
    }

    @Test
    void theParkedWarningRearmsWhenTheErrorHatIsLifted() {
        // 帽子被摘掉(重建基线把状态写回 RUNNING)后,这个项目若再次被 park,必须重新警告;
        // 否则"只警告一次"会变成"整个进程只警告一次",下一次故障就没人看得见。
        Project project = cdcProject(9L, true);
        when(taskRepository.findByProjectId(9L)).thenReturn(Optional.of(parkedTask(9L)));
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(null); // 他节点持有,不真起线程

        ListAppender<ILoggingEvent> logs = LogCapture.attach(CdcConsumerManager.class);
        try {
            manager.ensureStarted(project);                      // 第 1 段停机:警告
            SyncTask running = parkedTask(9L);
            running.setStatus(TaskStatus.RUNNING);
            when(taskRepository.findByProjectId(9L)).thenReturn(Optional.of(running));
            manager.ensureStarted(project);                      // 帽子已摘:清掉去重标记
            when(taskRepository.findByProjectId(9L)).thenReturn(Optional.of(parkedTask(9L)));
            manager.ensureStarted(project);                      // 第 2 段停机:再警告

            assertThat(LogCapture.messagesAt(Level.WARN, logs))
                    .as("两段独立的 ERROR 停机各警告一次")
                    .hasSize(2);
        } finally {
            LogCapture.detach(CdcConsumerManager.class, logs);
        }
    }

    private static SyncTask parkedTask(long projectId) {
        SyncTask task = new SyncTask();
        task.setProjectId(projectId);
        task.setStatus(TaskStatus.ERROR);
        return task;
    }

    @Test
    void superviseRetriesAProjectAfterATransientStartFailure() {
        // 不 park 的意义就在这一条:下一轮 supervise 必须再试一次。旧实现第一轮就把项目
        // 写进 ERROR,第二轮被 hasErrorHat 挡在启动之外,永远不再 build。
        Project project = cdcProject(9L, true);
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project));
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        when(contextFactory.build(any(), anyString(), any())).thenThrow(new IllegalStateException(
                "Cannot connect to 'src' to determine its schema: Connection refused",
                new java.sql.SQLException("Connection refused")));

        manager.supervise();
        manager.supervise();

        verify(contextFactory, times(2)).build(any(), anyString(), any());
        verify(lockService, times(2)).tryAcquireForDaemon(9L, 30_000L);
        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
    }

    @Test
    void ensureStoppedJoinsBeforeReturn() throws Exception {
        // Review Focus #5:切策略时 ensureStopped 必须同步等消费者停下——否则旧流与新轮询双写。
        // 假 client:connect 阻塞到 disconnect 被调(模拟真实流式连接),线程退出只可能经 requestStop。
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        java.util.concurrent.atomic.AtomicBoolean streaming =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
        com.github.shyiko.mysql.binlog.BinaryLogClient client =
                mock(com.github.shyiko.mysql.binlog.BinaryLogClient.class);
        org.mockito.Mockito.doAnswer(inv -> {
            streaming.set(true);
            connected.countDown();
            while (streaming.get()) {
                Thread.sleep(50);
            }
            return null;
        }).when(client).connect(anyLong());
        org.mockito.Mockito.doAnswer(inv -> {
            streaming.set(false);
            return null;
        }).when(client).disconnect();
        when(client.isConnected()).thenReturn(true);   // requestStop 走 disconnect 分支
        when(clientFactory.createClient(any(), anyLong(), any())).thenReturn(client);

        manager.ensureStarted(cdcProject(9L, true));
        assertThat(connected.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        manager.ensureStopped(9L);

        assertThat(starter.lastThread.isAlive()).isFalse();  // join 生效:返回时线程已退
        assertThat(manager.statsFor(9L)).isNull();           // 注册表已清
        verify(lock, timeout(2000)).close();                 // 租约已释放
        verify(taskStore, never()).recordCdcFailure(anyLong(), any()); // 是干净停机,不是致命错
    }

    @Test
    void superviseTakesOverEnabledCdcProjectWithoutConsumer() {
        Project project = cdcProject(9L, true);
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project));
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        manager.supervise();
        assertThat(starter.last).isNotNull(); // 接管路径:锁过期 → supervise 抢到 → 启动
        manager.ensureStopped(9L);
    }

    @Test
    void superviseStopsConsumerWhenProjectSwitchedAway() {
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        manager.ensureStarted(cdcProject(9L, true));
        Project nowPolling = cdcProject(9L, true);
        nowPolling.setSyncConfig("{\"captureStrategy\":\"POLLING\"}");
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(nowPolling));
        manager.supervise();
        assertThat(manager.statsFor(9L)).isNull(); // 收敛:非 CDC 项目不留消费者
    }

    @Test
    void supervisePrunesDeadRegistrationAndRestarts() {
        // R2(控制器裁定):线程已死的注册项(例如 lease 丢失后的安静出口)必须在收敛逻辑
        // 之前除名,否则本节点永远无法再次参与接管(B6 failback)。ERROR 帽子仍由
        // ensureStarted 的既有守卫挡住重启——这里没有 ERROR 帽子,所以除名后应重启。
        CdcConsumerManager deadThreadManager = new CdcConsumerManager(projectRepository,
                taskRepository, lockService, contextFactory, clientFactory, positionStore,
                baselineService, taskStore, stateWriter, dataSourceManager, new SyncProperties(),
                (consumer, threadName) -> new Thread(consumer, threadName),
                (source, startScn, batchSize) -> {
                    throw new UnsupportedOperationException();
                }); // 从不 start → isAlive()==false
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);
        when(taskRepository.findByProjectId(9L)).thenReturn(Optional.empty());
        Project project = cdcProject(9L, true);

        deadThreadManager.ensureStarted(project);                    // 注册一个死线程
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project));
        deadThreadManager.supervise();                               // 除名 → 重新抢锁 → 重启

        verify(lockService, times(2)).tryAcquireForDaemon(9L, 30_000L);
    }

    // —— v1.6.0 双策略分派(LOGMINER_CDC) ——

    @Test
    void ensureStartedDispatchesLogMinerConsumerForLogMinerCdcStrategy() {
        // Task 5 Step 1(红→绿):LOGMINER_CDC 项目必须得到 LogMinerCdcConsumer,
        // 而不是 BINLOG_CDC 专属的 CdcConsumer;线程名前缀保持不变(中断纪律依赖它)。
        when(lockService.tryAcquireForDaemon(11L, 30_000L)).thenReturn(lock);
        Project oracle = projectWithStrategy(11L, true, CaptureStrategy.LOGMINER_CDC);

        manager.ensureStarted(oracle);

        assertThat(starter.last).isInstanceOf(LogMinerCdcConsumer.class);
        assertThat(starter.lastName).isEqualTo("jync-cdc-11");
        manager.ensureStopped(11L);
    }

    @Test
    void ensureStartedStillDispatchesBinlogConsumerForBinlogCdcStrategy() {
        // 对照:BINLOG_CDC 分派不变——接口抽取零行为变化的硬证明之一。
        when(lockService.tryAcquireForDaemon(9L, 30_000L)).thenReturn(lock);

        manager.ensureStarted(cdcProject(9L, true));

        assertThat(starter.last).isInstanceOf(CdcConsumer.class);
        assertThat(starter.lastName).isEqualTo("jync-cdc-9");
        manager.ensureStopped(9L);
    }

    @Test
    void superviseStartsBothLogCdcStrategiesButNotPolling() {
        // supervise 门已泛化(isLogCdc()):两种日志级策略都补起,POLLING 一概不建消费者。
        when(lockService.tryAcquireForDaemon(20L, 30_000L)).thenReturn(lock);
        when(lockService.tryAcquireForDaemon(21L, 30_000L)).thenReturn(lock);
        Project binlog = projectWithStrategy(20L, true, CaptureStrategy.BINLOG_CDC);
        Project oracle = projectWithStrategy(21L, true, CaptureStrategy.LOGMINER_CDC);
        Project polling = projectWithStrategy(22L, true, CaptureStrategy.POLLING);
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(binlog, oracle, polling));

        manager.supervise();

        assertThat(starter.last).isNotNull();
        // 两种日志策略都启动(顺序按 repository 返回序:20 后 21,最后一轮 starter.last=21)
        verify(lockService, times(1)).tryAcquireForDaemon(20L, 30_000L);
        verify(lockService, times(1)).tryAcquireForDaemon(21L, 30_000L);
        verify(lockService, never()).tryAcquireForDaemon(
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.anyLong());
        manager.ensureStopped(20L);
        manager.ensureStopped(21L);
    }
}
