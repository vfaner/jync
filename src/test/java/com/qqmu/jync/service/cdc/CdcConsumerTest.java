package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * B6/B7 消费者线程的两条控制器裁定:
 *
 * <ul>
 *   <li>R1 —— 租约丢失必须安静退出(INFO 日志 + 释放锁),绝不 recordCdcFailure/ERROR:
 *       接管节点可能已把状态标成 RUNNING,ERROR 会把它踩掉并让所有节点的 supervise
 *       因 ERROR-skip 冻结。</li>
 *   <li>R3 —— 消费者构造 applier 时必须带上 DataSyncService 同源的查询超时
 *       (SyncProperties.queryTimeoutSeconds),经 3 参构造传给每条语句。</li>
 * </ul>
 */
class CdcConsumerTest {

    private static Project project(long id) {
        Project project = new Project();
        project.setId(id);
        project.setName("cdc-" + id);
        return project;
    }

    private static CdcPosition baselineDonePosition(long projectId, String file) {
        CdcPosition position = new CdcPosition();
        position.setProjectId(projectId);
        position.setBinlogFile(file);
        position.setBinlogPos(4L);
        position.setBaselineDone(true);
        return position;
    }

    @Test
    void leaseLossExitsQuietlyWithoutError() throws Exception {
        Project project = project(7L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(false);                 // 租约已被他节点接管
        SyncContext ctx = SyncContext.builder()
                .project(project).config(new SyncConfig()).build();
        BinlogClientFactory clientFactory = mock(BinlogClientFactory.class);
        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        when(positionStore.find(7L))
                .thenReturn(Optional.of(baselineDonePosition(7L, "mysql-bin.000004")));
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);

        CdcConsumer consumer = new CdcConsumer(project, lock, ctx, clientFactory, positionStore,
                baselineService, taskStore, stateWriter, dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties());

        Thread thread = new Thread(consumer, "cdc-test-7");
        thread.start();
        thread.join(10_000);

        assertThat(thread.isAlive()).isFalse();               // 快速安静退出
        verify(baselineService, never()).runBaseline(any(), any()); // 基线已完成,不重跑
        verify(clientFactory, never()).createClient(any(), anyLong(), any()); // 不再建流
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());        // 绝不是致命错
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(ChangeType.ERROR), anyString(), anyBoolean(), anyLong());  // 绝不写 ERROR
        verify(stateWriter).recordLog(7L, ObjectType.PROJECT, "cdc-7", ChangeType.INFO,
                "CDC consumer stopped: lease lost", true, 0L);
        // 停机行必须有一条启动行与之配对:否则重启后的变更日志里只有「停止」，
        // 看起来像反复崩溃,而不是一次正常的进程重启。
        verify(stateWriter).recordLog(7L, ObjectType.PROJECT, "cdc-7", ChangeType.INFO,
                "CDC consumer started", true, 0L);
        verify(lock).close();                                  // 租约句柄仍按 owner 守卫释放
    }

    @Test
    void consumerBuildsItsApplierWithTheConfiguredQueryTimeout() throws SQLException {
        // R3:applier 的超时值与 DataSyncService 同源——SyncProperties.queryTimeoutSeconds。
        SyncProperties properties = new SyncProperties();
        properties.setQueryTimeoutSeconds(45);
        Project project = project(8L);
        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .build();
        CdcConsumer consumer = new CdcConsumer(project, mock(SyncLockService.LockHandle.class),
                ctx, mock(BinlogClientFactory.class), mock(CdcPositionStore.class),
                mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(SyncStateWriter.class), mock(DataSourceManager.class),
                new CdcRuntimeStats(), properties);

        Connection target = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(target.prepareStatement(anyString())).thenReturn(ps);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("name", "a");
        consumer.applierForTest().applyUpserts(target, pkBinding(), List.of(row));

        verify(ps, atLeastOnce()).setQueryTimeout(45);
    }

    // —— FIX B(评审 Important):supplier 不得吞掉瞬态元数据失败 ——
    // 返回 null =「表不受支持」→ handler 跳过该表行事件,事务照常提交、位点照常前进
    // = 静默永久丢行(at-least-once 被打破)。瞬态失败必须抛出:异常经监听器 →
    // onCommunicationFailure → TRANSIENT 分类 → 断连重连,从上次位点整事务重放。

    @Test
    void supplierPropagatesTransientMetadataFailureInsteadOfReturningNull() throws Exception {
        Project project = project(9L);
        SyncContext ctx = SyncContext.builder().project(project).config(new SyncConfig()).build();
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        SQLException poolDown = new SQLException("pool exhausted");
        when(dataSourceManager.getConnection(any())).thenThrow(poolDown);
        CdcConsumer consumer = new CdcConsumer(project, mock(SyncLockService.LockHandle.class),
                ctx, mock(BinlogClientFactory.class), mock(CdcPositionStore.class),
                mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties());

        assertThatThrownBy(() -> consumer.supplierForTest().binding("src", "orders"))
                .isInstanceOf(RuntimeException.class)
                .hasCause(poolDown);                             // 抛出(带原因),绝不返回 null
    }

    @Test
    void supplierStillReturnsNullForAGenuinelyAbsentTable() throws Exception {
        // 唯一合法的 null:表真的不在(information_schema 查无,例如流中途被 DROP)
        // → handler 按 unsupported 跳过该表行事件,流继续。
        Project project = project(10L);
        SyncContext ctx = SyncContext.builder().project(project).config(new SyncConfig()).build();
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSourceManager.getConnection(any())).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);                       // 表不存在
        CdcConsumer consumer = new CdcConsumer(project, mock(SyncLockService.LockHandle.class),
                ctx, mock(BinlogClientFactory.class), mock(CdcPositionStore.class),
                mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties());

        assertThat(consumer.supplierForTest().binding("src", "gone")).isNull();
    }

    // —— 裁定 R1(choke 路径端到端):handler 抛 → 自行断连 → consumer 先查 handler 失败
    // 引用(再查 lastFailure)→ 走既有 strike-out/分类逻辑。0.41.5 吞监听器异常且自愿断连
    // 不触发 onCommunicationFailure,所以引用是唯一真因载体 —— 不查它就是「静默挂死」。——

    /**
     * A mock client whose {@code connect()} synchronously feeds the registered handler a
     * poison TABLE_MAP event: the consumer's internal BindingSupplier trips over
     * {@code dataSourceManager.getConnection} (mocked to throw), the handler's choke path
     * stores the failure, "disconnects" and rethrows — the catch below mimics the real
     * 0.41.5 client swallowing the rethrow ("choked on", loop continues). Only the first
     * {@code poisonFirstNConnects} connects poison; later ones are clean connect→drop.
     */
    private static BinaryLogClient clientThatChokesOnConnect(AtomicBoolean connected,
            AtomicInteger connects, int poisonFirstNConnects) throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        AtomicReference<BinaryLogClient.EventListener> listener = new AtomicReference<>();
        AtomicReference<BinaryLogClient.LifecycleListener> lifecycle = new AtomicReference<>();
        doAnswer(inv -> {
            listener.set(inv.getArgument(0));
            return null;
        }).when(client).registerEventListener(any());
        doAnswer(inv -> {
            lifecycle.set(inv.getArgument(0));
            return null;
        }).when(client).registerLifecycleListener(any());
        when(client.isConnected()).thenAnswer(inv -> connected.get());
        doAnswer(inv -> {
            int n = connects.incrementAndGet();
            connected.set(true);
            lifecycle.get().onConnect(client);
            if (n <= poisonFirstNConnects) {
                TableMapEventData data = new TableMapEventData();
                data.setTableId(11L);
                data.setDatabase("app");
                data.setTable("orders");
                EventHeaderV4 header = new EventHeaderV4();
                header.setEventType(EventType.TABLE_MAP);
                header.setNextPosition(100L);
                header.setTimestamp(0L);
                try {
                    listener.get().onEvent(new Event(header, data));
                } catch (RuntimeException swallowed) {
                    // exactly what 0.41.5's notifyEventListeners does with it
                }
            }
            connected.set(false);
            return null;
        }).when(client).connect(anyLong());
        return client;
    }

    private static CdcConsumer chokeConsumer(Project project, SyncLockService.LockHandle lock,
                                             BinlogClientFactory factory,
                                             CdcPositionStore positionStore,
                                             SyncTaskStore taskStore, DataSourceManager dsm,
                                             SyncProperties props) {
        return chokeConsumer(project, lock, factory, positionStore, taskStore, dsm, props,
                new CdcRuntimeStats());
    }

    /** Same rig, but the caller keeps the stats reference (fix-round-1 assertions). */
    private static CdcConsumer chokeConsumer(Project project, SyncLockService.LockHandle lock,
                                             BinlogClientFactory factory,
                                             CdcPositionStore positionStore,
                                             SyncTaskStore taskStore, DataSourceManager dsm,
                                             SyncProperties props, CdcRuntimeStats stats) {
        SyncConfig config = new SyncConfig();
        config.getTables().add("orders");
        SyncContext ctx = SyncContext.builder().project(project).config(config)
                .sourceSchema("app").build();
        when(positionStore.find(project.getId())).thenReturn(
                Optional.of(baselineDonePosition(project.getId(), "mysql-bin.000009")));
        return new CdcConsumer(project, lock, ctx, factory, positionStore,
                mock(CdcBaselineService.class), taskStore, mock(SyncStateWriter.class), dsm,
                stats, props);
    }

    @Test
    void handlerChokeIsConsultedBeforeCommFailureAndClassifiedPurged() throws Exception {
        Project project = project(11L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true);                     // 租约始终健康:排除退出干扰
        DataSourceManager dsm = mock(DataSourceManager.class);
        // 分类走既有 PURGED 模式;MySQL 8 GTID 新措辞属裁定 R3,由其自带测试钉住
        when(dsm.getConnection(any())).thenThrow(new SQLException(
                "The slave is connecting using CHANGE MASTER TO MASTER_AUTO_POSITION = 1, but"
                        + " the master has purged binary logs containing GTIDs that the slave"
                        + " requires"));
        BinlogClientFactory factory = mock(BinlogClientFactory.class);
        BinaryLogClient client = clientThatChokesOnConnect(new AtomicBoolean(),
                new AtomicInteger(), Integer.MAX_VALUE);
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), taskStore, dsm, new SyncProperties());

        Thread thread = new Thread(consumer, "cdc-test-11");
        thread.start();
        thread.join(10_000);

        // 不查 handler 引用时本轮 failure 为 null(自愿断连不触发 onCommunicationFailure)
        // → TRANSIENT → 租约永真 → 无限重连,线程不可能结束。线程退出本身即证明引用被
        // 优先查询并进入了分类:purge 措辞 → PURGED → 致命停车。
        assertThat(thread.isAlive()).isFalse();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(taskStore).recordCdcFailure(eq(11L), detail.capture());
        assertThat(detail.getValue()).startsWith("PURGED:")
                .contains("master has purged binary logs");
        verify(client, atLeastOnce()).disconnect();              // choke 时 handler 已强制断连
    }

    @Test
    void repeatedPoisonEventsStrikeOutIntoFatalPark() throws Exception {
        Project project = project(12L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true);
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenThrow(new SQLException("target down"));
        BinlogClientFactory factory = mock(BinlogClientFactory.class);
        AtomicInteger connects = new AtomicInteger();
        BinaryLogClient client = clientThatChokesOnConnect(new AtomicBoolean(), connects,
                Integer.MAX_VALUE);
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncProperties props = new SyncProperties();
        props.getCdc().setReconnectBackoffMs(1L);                // 单测节奏:1ms 起步
        props.getCdc().setReconnectBackoffMaxMs(2L);
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), taskStore, dsm, props);

        Thread thread = new Thread(consumer, "cdc-test-12");
        thread.start();
        thread.join(10_000);

        assertThat(thread.isAlive()).isFalse();
        // 既有 strike-out 语义原样保留:>5 连败(其间无成功事务清零)才致命;
        // 第 1..5 次照常退避重连,第 6 次停车。
        assertThat(connects.get()).isEqualTo(6);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(taskStore).recordCdcFailure(eq(12L), detail.capture());
        assertThat(detail.getValue()).startsWith("DESERIALIZATION_STRIKE_OUT:")
                .contains("target down");
        verify(client, times(6)).disconnect();                   // 每次 choke 都当场断连
    }

    @Test
    void immediateDropsEscalateBackoffInsteadOfHotLoopReset() throws Exception {
        // 裁定 R3b:连上立刻被踢(存活 ~0ms)绝不许触发 backoff.reset —— 旧代码在
        // onConnect 无条件清零,accept-then-kick 的服务器把重试钉死在 1s 热循环;
        // 现在退避必须逐级爬升(1s→2s→4s…→60s 封顶)。
        Project project = project(14L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true, true, false);        // 第 3 轮租约丢失:安静退出
        BinlogClientFactory factory = mock(BinlogClientFactory.class);
        AtomicInteger connects = new AtomicInteger();
        BinaryLogClient client = clientThatChokesOnConnect(new AtomicBoolean(), connects, 0);
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncProperties props = new SyncProperties();
        props.getCdc().setReconnectBackoffMs(1L);                // 单测节奏:毫秒级退避
        props.getCdc().setReconnectBackoffMaxMs(2L);
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), taskStore, mock(DataSourceManager.class), props);

        Thread thread = new Thread(consumer, "cdc-test-14");
        thread.start();
        thread.join(10_000);

        assertThat(thread.isAlive()).isFalse();
        assertThat(connects.get()).isEqualTo(2);                 // 干净断连:failure=null → TRANSIENT
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());
        // 干净断连不喂毒事件、不清零:两次重试各把退避推进一级(1ms→2ms)。
        // 旧代码 onConnect 无条件 reset,退出时 attempt 只会是 1(甚至 0)。
        assertThat(consumer.backoffForTest().attemptForTest()).isEqualTo(2);
        assertThat(consumer.backoffForTest().nextDelayMs()).isEqualTo(2L); // 已爬到 2ms 封顶
    }

    @Test
    void singleTransientChokeReconnectsWithoutFatalPark() throws Exception {
        Project project = project(13L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true, true, false);        // 第 3 轮租约丢失:安静退出
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenThrow(new SQLException("target down"));
        BinlogClientFactory factory = mock(BinlogClientFactory.class);
        AtomicInteger connects = new AtomicInteger();
        BinaryLogClient client = clientThatChokesOnConnect(new AtomicBoolean(), connects, 1);
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncProperties props = new SyncProperties();
        props.getCdc().setReconnectBackoffMs(1L);
        props.getCdc().setReconnectBackoffMaxMs(2L);
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), taskStore, dsm, props);

        Thread thread = new Thread(consumer, "cdc-test-13");
        thread.start();
        thread.join(10_000);

        assertThat(thread.isAlive()).isFalse();
        // 单次瞬态失败:分类 TRANSIENT → 退避重连(第 2 次 connect 干净)→ 绝不致命停车
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());
        assertThat(connects.get()).isEqualTo(2);
    }

    // —— Fix round 1(C3 真实性):markStarted/setLastError 此前零生产调用者,面板上
    // 「吞吐」永远 0.0、「最近错误」永远不渲染。接线:onConnect → markStarted +
    // setLastError(null)(窗口从真实建流起算、重连即清除过期错误);三个失败落点
    // (瞬态重试、fatalStop、catch-all)→ setLastError(与 taskStore 同款 describe 措辞)。——

    /** Polls until the condition holds; every use below runs while the consumer thread is parked on a gate, so this waits, never races. */
    private static void pollUntil(BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void connectStampsTheThroughputWindowSoTheRateBecomesObservable() throws Exception {
        Project project = project(15L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true, false);              // 一轮干净建流后租约丢失退出
        BinlogClientFactory factory = mock(BinlogClientFactory.class);
        BinaryLogClient client = clientThatChokesOnConnect(new AtomicBoolean(),
                new AtomicInteger(), 0);                          // 干净 connect→drop
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);
        SyncProperties props = new SyncProperties();
        props.getCdc().setReconnectBackoffMs(1L);
        props.getCdc().setReconnectBackoffMaxMs(2L);
        CdcRuntimeStats stats = new CdcRuntimeStats();
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), mock(SyncTaskStore.class),
                mock(DataSourceManager.class), props, stats);

        Thread thread = new Thread(consumer, "cdc-test-15");
        thread.start();
        thread.join(10_000);
        assertThat(thread.isAlive()).isFalse();

        // 被测接线是 onConnect 里的 markStarted():rows 由测试喂入只为让速率可观察。
        // eventsPerSecond() 有 1 秒地板(首秒恒 0.0),所以「>0」只能等过 1 秒断言——
        // 本文件唯一的一次 sleep;对照组(从未 markStarted)同睡同行数,恒 0.0。
        stats.addUpserts(5);
        CdcRuntimeStats neverStarted = new CdcRuntimeStats();
        neverStarted.addUpserts(5);
        Thread.sleep(1_050);
        assertThat(stats.eventsPerSecond()).isGreaterThan(0.0);
        assertThat(neverStarted.eventsPerSecond()).isEqualTo(0.0);
    }

    @Test
    void fatalParkPublishesTheSameLastErrorIntoTheSnapshot() throws Exception {
        Project project = project(16L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true);
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenThrow(new SQLException(
                "The slave is connecting using CHANGE MASTER TO MASTER_AUTO_POSITION = 1, but"
                        + " the master has purged binary logs containing GTIDs that the slave"
                        + " requires"));
        BinlogClientFactory factory = mock(BinlogClientFactory.class);
        BinaryLogClient client = clientThatChokesOnConnect(new AtomicBoolean(),
                new AtomicInteger(), Integer.MAX_VALUE);
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        CdcRuntimeStats stats = new CdcRuntimeStats();
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), taskStore, dsm, new SyncProperties(), stats);

        Thread thread = new Thread(consumer, "cdc-test-16");
        thread.start();
        thread.join(10_000);
        assertThat(thread.isAlive()).isFalse();                  // PURGED → fatalStop 停车

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(taskStore).recordCdcFailure(eq(16L), detail.capture());
        assertThat(detail.getValue()).startsWith("PURGED:");
        // 面板与 sync_task 收到同一份措辞;snapshot 是面板的消费面。
        assertThat(stats.getLastError()).isEqualTo(detail.getValue());
        assertThat(stats.snapshot().get("lastError")).isEqualTo(detail.getValue());
    }

    @Test
    void transientFailureSetsLastErrorAndTheReconnectClearsIt() throws Exception {
        Project project = project(17L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true, true, false);        // 第 3 轮租约丢失:安静退出
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenThrow(new SQLException("target down"));
        BinlogClientFactory factory = mock(BinlogClientFactory.class);

        // 定制 mock 客户端:第 1 次 connect 喂毒事件(choke → TRANSIENT → 重试路径写
        // lastError);第 2 次 connect 先停在 gate1(测试断言错误可见),放行后 onConnect
        // 清空 lastError,再停在 gate2(测试断言清空)。两个断言点线程都停在门上——零竞态。
        BinaryLogClient client = mock(BinaryLogClient.class);
        AtomicBoolean connected = new AtomicBoolean();
        AtomicInteger connects = new AtomicInteger();
        AtomicReference<BinaryLogClient.EventListener> listener = new AtomicReference<>();
        AtomicReference<BinaryLogClient.LifecycleListener> lifecycle = new AtomicReference<>();
        CountDownLatch gate1 = new CountDownLatch(1);
        CountDownLatch gate2 = new CountDownLatch(1);
        doAnswer(inv -> {
            listener.set(inv.getArgument(0));
            return null;
        }).when(client).registerEventListener(any());
        doAnswer(inv -> {
            lifecycle.set(inv.getArgument(0));
            return null;
        }).when(client).registerLifecycleListener(any());
        when(client.isConnected()).thenAnswer(inv -> connected.get());
        doAnswer(inv -> {
            int n = connects.incrementAndGet();
            if (n == 2) {
                gate1.await(10, TimeUnit.SECONDS);
            }
            connected.set(true);
            lifecycle.get().onConnect(client);
            if (n == 1) {
                TableMapEventData data = new TableMapEventData();
                data.setTableId(11L);
                data.setDatabase("app");
                data.setTable("orders");
                EventHeaderV4 header = new EventHeaderV4();
                header.setEventType(EventType.TABLE_MAP);
                header.setNextPosition(100L);
                header.setTimestamp(0L);
                try {
                    listener.get().onEvent(new Event(header, data));
                } catch (RuntimeException swallowed) {
                    // exactly what 0.41.5's notifyEventListeners does with it
                }
            }
            if (n == 2) {
                gate2.await(10, TimeUnit.SECONDS);
            }
            connected.set(false);
            return null;
        }).when(client).connect(anyLong());
        when(factory.createClient(any(), anyLong(), any())).thenReturn(client);

        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncProperties props = new SyncProperties();
        props.getCdc().setReconnectBackoffMs(1L);
        props.getCdc().setReconnectBackoffMaxMs(2L);
        CdcRuntimeStats stats = new CdcRuntimeStats();
        CdcConsumer consumer = chokeConsumer(project, lock, factory,
                mock(CdcPositionStore.class), taskStore, dsm, props, stats);

        Thread thread = new Thread(consumer, "cdc-test-17");
        thread.start();

        // ① 断流期间(线程停在 gate1):lastError 必须可见,措辞即 describe 的根因消息。
        pollUntil(() -> stats.getLastError() != null, "lastError to be set");
        assertThat(stats.getLastError()).contains("target down");
        assertThat(stats.snapshot().get("lastError")).isEqualTo(stats.getLastError());

        // ② 放行:第 2 次 onConnect 清空 lastError(线程随后停在 gate2,清空态稳定)。
        gate1.countDown();
        pollUntil(() -> stats.getLastError() == null, "lastError to be cleared on reconnect");

        gate2.countDown();
        thread.join(10_000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(connects.get()).isEqualTo(2);
        verify(taskStore, never()).recordCdcFailure(anyLong(), any()); // 瞬态:绝不 ERROR 停车
    }

    // —— 终审 FIX 2:stop-requested 的基线中止必须安静退出 ——
    // ensureStopped 的 interrupt 落在基线分块全量中途:中止沿 runCycle(折成失败 result)
    // → runBaseline(保留中断位重抛)传到 run() 的 catch-all。此时 running=false 或中断位
    // 在 → 绝不是致命错:不 ERROR 帽子、不 recordCdcFailure、不写 ERROR ChangeLog;
    // 基线保持 not-done,租约照常释放,下次启动重跑全量。

    @Test
    void interruptedBaselineExitsQuietlyWithoutFatalPark() throws Exception {
        Project project = project(18L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        when(positionStore.find(18L)).thenReturn(Optional.empty());  // 无位点 → 先跑基线
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        doAnswer(inv -> {
            Thread.currentThread().interrupt();                      // ensureStopped 的 interrupt
            throw new IllegalStateException(
                    "CDC baseline full load failed: stop requested (thread interrupted)");
        }).when(baselineService).runBaseline(any(), any());
        BinlogClientFactory clientFactory = mock(BinlogClientFactory.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        SyncContext ctx = SyncContext.builder()
                .project(project).config(new SyncConfig()).build();

        CdcConsumer consumer = new CdcConsumer(project, lock, ctx, clientFactory, positionStore,
                baselineService, taskStore, stateWriter, mock(DataSourceManager.class),
                new CdcRuntimeStats(), new SyncProperties());

        Thread thread = new Thread(consumer, "cdc-test-18");
        thread.start();
        thread.join(10_000);

        assertThat(thread.isAlive()).isFalse();                      // 安静快速退出
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());        // 绝不 ERROR 帽子
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(ChangeType.ERROR), anyString(), anyBoolean(), anyLong());  // 绝不写 ERROR
        verify(clientFactory, never()).createClient(any(), anyLong(), any()); // 未进入流式阶段
        verify(lock).close();                                        // finally 照常释放租约
        verify(stateWriter).recordLog(18L, ObjectType.PROJECT, "cdc-18", ChangeType.INFO,
                "CDC consumer stopped", true, 0L);                   // 普通 INFO 停机行
    }

    private static CdcTableBinding pkBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta name = new ColumnMeta();
        name.setName("name");
        name.setJdbcType(Types.VARCHAR);
        meta.getColumns().add(id);
        meta.getColumns().add(name);
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("src", "orders", meta, "orders", null);
    }
}
