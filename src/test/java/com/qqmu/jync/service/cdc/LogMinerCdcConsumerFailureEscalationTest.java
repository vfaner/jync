package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * F-12(代码审查对照 F-10):LogMiner 消费者只有<b>解析</b>失败计数
 * (parseFailures/PARSE_STRIKE_OUT),目标端 flush 失败(如目标缺列 SQLException
 * 42703-类、连接被目标拒绝)在 cycle catch(LogMinerCdcConsumer line 169)里只记
 * lastFailure,不增任何计数器;升级判定只看 TRANSIENT 与 parseStrikeOut。于是毒
 * 事务周期失败 → 退避 → 下周期失败,消费者<b>空转无限循环</b>,既不 park ERROR 也
 * 永不前进 —— 与 F-10 修复前的 PG 消费者同款。
 *
 * <p>契约(本测试钉住,与 PG F-10 / MySQL deserializationFailures 同阈值同语义):
 * <ul>
 *   <li>连续 {@code > 5} 个失败周期(无成功事务夹在中间)→ recordCdcFailure +
 *       park ERROR,标签 APPLY_STRIKE_OUT,线程自行结束;</li>
 *   <li>一个成功落库的事务必须把连续 apply 失败计数清零 —— 3 败 + 1 成 + 3 败
 *       不到阈值,不得被老账 park(偶发抖动后长期健康运行的常态)。</li>
 * </ul>
 */
class LogMinerCdcConsumerFailureEscalationTest {

    private static final String H2_URL =
            "jdbc:h2:mem:lm_escalation;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection source;
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncTaskStore taskStore;
    private SyncProperties properties;
    private final AtomicInteger cycle = new AtomicInteger();

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    private static Connection h2conn() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }

        source = mock(Connection.class);
        // OracleCdbSupport probes (V$DATABASE.CDB / SYS_CONTEXT CON_ID) — empty results
        // make the plan indeterminate/non-CDB, i.e. the SOURCE mining path; no ROOT.
        Statement probeStmt = mock(Statement.class);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(probeStmt.executeQuery(anyString())).thenReturn(emptyRs);
        when(source.createStatement()).thenReturn(probeStmt);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = new CdcPosition();
        anchored.setProjectId(1L);
        anchored.setScn(1_000L);
        anchored.setBaselineDone(true);
        when(positionStore.find(1L)).thenReturn(java.util.Optional.of(anchored));

        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);

        taskStore = mock(SyncTaskStore.class);
        properties = new SyncProperties();
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getLogminer().setIdlePollMs(1);
    }

    /** One INSERT row per cycle; fresh id/csn so successful cycles really land a row. */
    private LogMinerSession oneInsertSession() {
        int n = cycle.get() + 1;
        String redo = "insert into \"SRC\".\"ORDERS\"(\"ID\",\"NAME\") values ('"
                + n + "','row-" + n + "');";
        LogMinerSession.Row row = new LogMinerSession.Row("rs", 1_000L + n,
                "INSERT", "SRC", "ORDERS", redo, new Timestamp(System.currentTimeMillis()), 0);
        return new LogMinerSession(null, 1_000L, 1_000L, null, null) {
            private boolean consumed;

            @Override
            public java.util.List<LogMinerSession.Row> nextBatch() {
                if (consumed) {
                    return Collections.emptyList();
                }
                consumed = true;
                return Collections.singletonList(row);
            }

            @Override
            public long highWaterScn() {
                return 1_000L + n;
            }

            @Override
            public void close() {
                // real close() runs END_LOGMNR on the super's (null) source connection
            }
        };
    }

    private TableMeta ordersMeta() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("ID", Types.BIGINT, true));
        meta.getColumns().add(column("NAME", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("ID");
        return meta;
    }

    private LogMinerCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock) {
        return newConsumer(ctx, lock, new CdcRuntimeStats());
    }

    private LogMinerCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock,
                                             CdcRuntimeStats stats) {
        Project project = new Project();
        project.setId(1L);
        project.setName("lm-1");
        LogMinerSessionFactory factory = (c, s, b) -> oneInsertSession();
        return new LogMinerCdcConsumer(project, lock, ctx, positionStore,
                mock(CdcBaselineService.class), taskStore,
                mock(com.qqmu.jync.service.sync.SyncStateWriter.class), dataSourceManager,
                stats, properties, factory);
    }

    /** A consumer whose every mining session is the supplied one. */
    private LogMinerCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock,
                                             LogMinerSession session) {
        Project project = new Project();
        project.setId(1L);
        project.setName("lm-1");
        LogMinerSessionFactory factory = (c, s, b) -> session;
        return new LogMinerCdcConsumer(project, lock, ctx, positionStore,
                mock(CdcBaselineService.class), taskStore,
                mock(com.qqmu.jync.service.sync.SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), properties, factory);
    }

    /**
     * 按 mine 周期编排目标端:脚本用尽后保持最后一种状态。每周期恰开一个事务,
     * 所以 open() 次数 == 周期数。
     */
    private SyncContext stagedTargetContext(final boolean... cycleSucceeds) throws SQLException {
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(ordersMeta());
        return SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new com.qqmu.jync.model.DatabaseConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> {
                    int n = cycle.getAndIncrement();
                    boolean ok = n < cycleSucceeds.length
                            ? cycleSucceeds[n]
                            : cycleSucceeds[cycleSucceeds.length - 1];
                    if (ok) {
                        return h2conn();
                    }
                    throw new SQLException("target column missing (42703)");
                })
                .build();
    }

    /** Lock renews for {@code grants} cycles, then refuses — bounds the loop in every path. */
    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }

    /**
     * Same bound, but samples the status panel at every cycle boundary: renew runs once at
     * the top of each loop iteration, which is exactly when an operator's browser sees stats.
     */
    private static SyncLockService.LockHandle samplingLock(CdcRuntimeStats stats,
                                                           List<Boolean> panelAtCycleStart,
                                                           int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> {
            panelAtCycleStart.add(stats.isConnected());
            return remaining.decrementAndGet() >= 0;
        });
        return lock;
    }

    private int targetRowCount() throws SQLException {
        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void repeatedTargetFlushFailuresEscalateToAnErrorPark() throws Exception {
        // 目标每轮拒绝 → drain 每轮抛 TRANSIENT。第 6 个连续失败周期必须升级
        // (recordCdcFailure + park APPLY_STRIKE_OUT),run() 自行结束,
        // 而不是靠 lock 失效安静退出。
        LogMinerCdcConsumer consumer = newConsumer(stagedTargetContext(
                false, false, false, false, false, false, false, false), boundedLock(20));

        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L), contains("target column missing"));
        verify(taskStore).recordCdcFailure(eq(1L), contains("APPLY_STRIKE_OUT"));
    }

    @Test
    void aSuccessfulTransactionResetsTheFailureEscalation() throws Exception {
        // 3 败 → 1 成 → 3 败(之后保持失败):任一连续段都不到阈值,lock 到期安静
        // 退出,全程绝不 park;且成功周期的行必须真正落库(证明确实 flush 成功)。
        LogMinerCdcConsumer consumer = newConsumer(
                stagedTargetContext(false, false, false, true, false, false, false),
                boundedLock(8));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
        assertThat(targetRowCount()).isEqualTo(1);
    }

    /** 一个不含任何变更的会话。 */
    private LogMinerSession emptySession() {
        return new LogMinerSession(null, 1_000L, 1_000L, null, null) {
            @Override
            public java.util.List<LogMinerSession.Row> nextBatch() {
                return Collections.emptyList();
            }

            @Override
            public long highWaterScn() {
                return 1_000L;
            }

            @Override
            public void close() {
                // real close() runs END_LOGMNR on the super's (null) source connection
            }
        };
    }

    @Test
    void aHealthyIdleSessionResetsTheFailureEscalation() throws Exception {
        // 5 败 → 1 个无异常但 nextBatch 为空的空闲会话 → 5 败。计数器语义是
        // "连续失败周期",而旧实现只在事务落库时清零:被同步的表空闲时没有
        // flush,网络抖动留下的旧账一直累积,最终把健康项目 APPLY_STRIKE_OUT
        // 永久 park。空会话成功开/关证明连接与挖掘通路健康。
        AtomicInteger sessions = new AtomicInteger();
        LogMinerSessionFactory factory = (c, s, b) -> sessions.getAndIncrement() == 5
                ? emptySession() : oneInsertSession();
        Project project = new Project();
        project.setId(1L);
        project.setName("lm-1");
        LogMinerCdcConsumer consumer = new LogMinerCdcConsumer(project, boundedLock(11),
                stagedTargetContext(false, false, false, false, false, false, false,
                        false, false, false),
                positionStore, mock(CdcBaselineService.class), taskStore,
                mock(SyncStateWriter.class), dataSourceManager, new CdcRuntimeStats(),
                properties, factory);

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
    }

    @Test
    void aHealthyCycleLeavesLastErrorNullInsteadOfReportingDrained() throws Exception {
        // lastError 是"最近错误"通道:模板在非 null 时渲染成红色错误行。健康周期
        // (LogMiner 默认 idle-poll 1000ms)旧实现每轮写 "mining cycle drained" ——
        // 每个正常运行的项目常驻一条红色"最近错误",且每轮 WARN,空闲即刷屏。
        // 契约:健康周期 lastError 保持 null、日志降到 DEBUG;失败周期才写并 WARN。
        CdcRuntimeStats stats = new CdcRuntimeStats();
        LogMinerCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), boundedLock(3), stats);
        ListAppender<ILoggingEvent> logs = LogCapture.attach(LogMinerCdcConsumer.class);
        try {
            consumer.run();
        } finally {
            LogCapture.detach(LogMinerCdcConsumer.class, logs);
        }

        assertThat(stats.getLastError()).isNull();
        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .noneMatch(message -> message.contains("cycle for project"));
    }

    // —— 状态面板真实性:轮询式消费者的 connected 必须粘性、吞吐窗口只标一次 ——

    @Test
    void aHealthyPollKeepsTheStatusPanelConnectedBetweenCycles() throws Exception {
        // 面板的 connected 圆点表示「这条 CDC 通路是否健康」。LogMiner 消费者每轮
        // mine 才开一次源连接,旧实现每轮 finally 置 false → 面板在健康运行的绝大
        // 多数时间都显示红色「已断开」(5 秒刷新几乎不可能撞上几毫秒的连接窗口)。
        // 契约:上一轮成功后保持 connected,只有失败周期才落回 false。
        //
        // 采样点取在每轮开头的 lock.renew —— 面板就在这个时刻看 stats。
        CdcRuntimeStats stats = new CdcRuntimeStats();
        List<Boolean> panelAtCycleStart = new ArrayList<>();
        LogMinerCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), samplingLock(stats, panelAtCycleStart, 3), stats);

        consumer.run();

        // 第 4 个采样是租约失效时的退出判定,仍反映第 3 轮结束后的状态。
        assertThat(panelAtCycleStart).containsExactly(false, true, true, true);
    }

    @Test
    void aFailedPollCycleDropsTheStatusPanelToDisconnected() throws Exception {
        // 失败周期必须如实反映在面板上:目标拒绝落库的那一轮之后,下一轮开头应看到
        // connected=false;再成功一轮后又回到 true。
        CdcRuntimeStats stats = new CdcRuntimeStats();
        List<Boolean> panelAtCycleStart = new ArrayList<>();
        LogMinerCdcConsumer consumer = newConsumer(
                stagedTargetContext(false, true, true), samplingLock(stats, panelAtCycleStart, 3),
                stats);

        consumer.run();

        assertThat(panelAtCycleStart).containsExactly(false, false, true, true);
    }

    @Test
    void theThroughputWindowIsStampedOncePerRunNotPerPollCycle() throws Exception {
        // 吞吐:eventsPerSecond() = 自 markStarted 起的应用行数 ÷ 已过秒数。旧实现
        // 每轮 mine 都 markStarted,分子却是消费者启动以来的累计行数 —— 采样落在
        // 一轮开头显示 0.0,落在 1 秒之后则是一个永远在涨的天文数字。
        // 让首轮建连耗时 1.1 秒:只有「窗口从 run 起点算」才能越过事件的 1 秒地板。
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.addUpserts(10);
        AtomicInteger connects = new AtomicInteger();
        when(dataSourceManager.getConnection(any())).thenAnswer(inv -> {
            if (connects.getAndIncrement() == 0) {
                Thread.sleep(1_100L);
            }
            return source;
        });
        LogMinerCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), boundedLock(2), stats);

        consumer.run();

        assertThat(stats.eventsPerSecond()).isGreaterThan(0.0);
    }

    @Test
    void baselineLeaseLossExitsQuietlyWithoutError() throws Exception {
        // 基线全量期间租约被接管:runBaseline 抛 LockLostException。R1 要求安静退出 ——
        // 不得 recordCdcFailure / 写 ERROR 行(接管节点可能已把状态标成 RUNNING)。
        when(positionStore.find(1L)).thenReturn(java.util.Optional.empty());
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        doThrow(new LockLostException("lease lost mid-baseline"))
                .when(baselineService).runBaseline(any(), any());
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(false);
        Project project = new Project();
        project.setId(1L);
        project.setName("lm-1");
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new com.qqmu.jync.model.DatabaseConfig())
                .build();

        LogMinerCdcConsumer consumer = new LogMinerCdcConsumer(project, lock, ctx,
                positionStore, baselineService, taskStore, stateWriter, dataSourceManager,
                new CdcRuntimeStats(), properties, (c, s, b) -> null);

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(ChangeType.ERROR), anyString(), anyBoolean(), anyLong());
        verify(lock).close();
    }

    @Test
    void requestStopDoesNotCloseTheSessionFromTheCallingThread() throws Exception {
        // 会话的 JDBC 对象(Connection/PreparedStatement/ResultSet)属于消费者线程。
        // 从 Web 线程的 requestStop 里调用 close() 会在消费者正遍历游标时跨线程执行
        // END_LOGMNR、关闭游标 —— 同一个 Connection 的并发使用是未定义行为,还会把
        // 请求线程阻塞在数据库往返上。停止只需翻标志:消费者线程在 finally 里关闭会话,
        // 与 SqlServerCdcConsumer.requestStop 的既有结论一致。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        List<String> closeThreads = Collections.synchronizedList(new ArrayList<>());
        LogMinerSession session = new LogMinerSession(null, 1_000L, 1_000L, null, null) {
            private boolean first = true;

            @Override
            public List<LogMinerSession.Row> nextBatch() {
                if (first) {
                    first = false;
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Collections.emptyList();
            }

            @Override
            public long highWaterScn() {
                return 1_000L;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    closes.incrementAndGet();
                    closeThreads.add(Thread.currentThread().getName());
                }
            }
        };
        LogMinerCdcConsumer consumer = newConsumer(stagedTargetContext(true),
                boundedLock(2), session);

        Thread worker = new Thread(consumer::run, "lm-consumer-under-test");
        worker.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            consumer.requestStop();
            assertThat(closes.get())
                    .as("requestStop closed the session from the calling thread")
                    .isZero();
        } finally {
            release.countDown();
            worker.join(5_000L);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(closes.get()).isEqualTo(1);
        assertThat(closeThreads).containsExactly("lm-consumer-under-test");
    }
}
