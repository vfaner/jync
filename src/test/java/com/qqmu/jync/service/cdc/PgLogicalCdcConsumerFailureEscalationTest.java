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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * F-10(docker 实测 PG→openGauss):源库在停机期 ADD COLUMN 后,新行的 upsert 带着
 * 目标端不存在的列 —— openGauss 每轮拒绝(SQLSTATE 42703),该 SQLException 被
 * 分类为 TRANSIENT。旧行为:drain 失败 → 退避 → 再 drain 同样失败,消费者<b>空转
 * 无限循环</b>,既不落数据也不 park ERROR。MySQL 消费者靠「连续 N 个失败周期」的
 * strike-out 兜底;PG 消费者只有解析失败计数,目标 flush 失败永远不升级。
 *
 * <p>契约(本测试钉住):
 * <ul>
 *   <li>连续 {@code > 5} 个失败周期(无成功事务夹在中间)→ recordCdcFailure +
 *       park ERROR,线程结束(与 MySQL deserializationStrikeOut 同阈值同语义);</li>
 *   <li>一个成功落库的事务必须把连续失败计数清零 —— 3 败 + 1 成 + 3 败不到阈值,
 *       不得被老账 park(偶发抖动后长期健康运行的常态)。</li>
 * </ul>
 */
class PgLogicalCdcConsumerFailureEscalationTest {

    private static final String H2_URL =
            "jdbc:h2:mem:pg_escalation;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection source;
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncTaskStore taskStore;
    private SyncProperties properties;
    private final AtomicLong nextId = new AtomicLong(1);

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
        when(source.getAutoCommit()).thenReturn(true);
        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(source.prepareStatement(anyString())).thenReturn(changesPs);
        // 每个 drain 周期都拉到一个全新的 BEGIN/INSERT/COMMIT 事务(模拟失败后 slot
        // 回卷,同一毒事务下周期再来;成功周期的行则带新 id 真正落库)。
        when(changesPs.executeQuery()).thenAnswer(inv -> oneTransactionResultSet());

        // ensureSlot:SELECT 说 slot 已存在,不建槽。
        Statement slotStmt = mock(Statement.class);
        ResultSet slotRs = mock(ResultSet.class);
        when(slotRs.next()).thenReturn(true);
        when(slotStmt.executeQuery(anyString())).thenReturn(slotRs);
        when(source.createStatement()).thenReturn(slotStmt);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = mock(CdcPosition.class);
        when(anchored.getLsn()).thenReturn("0/1000");
        when(anchored.isBaselineDone()).thenReturn(true);
        when(positionStore.find(1L)).thenReturn(java.util.Optional.of(anchored));

        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);

        taskStore = mock(SyncTaskStore.class);
        properties = new SyncProperties();
        // 新节奏:失败周期走指数退避(不再走 idle poll),成功且捞到行立即下一轮。
        // 与 LogMiner/SqlServer 两个升级测试同口径:两个旋钮都压到 1ms。
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getPg().setIdlePollMs(1);
    }

    /** BEGIN/INSERT/COMMIT 三行的一次性结果集,每行 id 取序列保证互不干扰。 */
    private ResultSet oneTransactionResultSet() throws SQLException {
        long id = nextId.getAndIncrement();
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString(1)).thenReturn("0/2000", "0/2001", "0/2002");
        when(rs.getString(2)).thenReturn(
                "BEGIN 12345",
                "table public.orders: INSERT: id[bigint]:" + id
                        + " name[text]:'row-" + id + "'",
                "COMMIT 12345");
        return rs;
    }

    private TableMeta ordersMeta() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    private PgLogicalCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock) {
        return newConsumer(ctx, lock, new CdcRuntimeStats());
    }

    private PgLogicalCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock,
                                             CdcRuntimeStats stats) {
        Project project = new Project();
        project.setId(1L);
        project.setName("pg-1");
        return new PgLogicalCdcConsumer(project, lock, ctx, positionStore,
                mock(CdcBaselineService.class), taskStore,
                mock(SyncStateWriter.class), dataSourceManager,
                stats, properties);
    }

    /**
     * 按 drain 周期编排目标端:脚本用尽后保持最后一种状态。每个周期恰好一个事务,
     * 所以 open() 调用次数 == 周期数。
     */
    private SyncContext stagedTargetContext(final boolean... cycleSucceeds) throws SQLException {
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(ordersMeta());
        final AtomicInteger cycle = new AtomicInteger();
        return SyncContext.builder()
                .config(new SyncConfig())
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
        // (recordCdcFailure + park),run() 自行结束,而不是靠 lock 失效安静退出。
        PgLogicalCdcConsumer consumer = newConsumer(stagedTargetContext(
                false, false, false, false, false, false, false, false), boundedLock(20));

        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L), contains("target column missing"));
    }

    @Test
    void sourceConnectFailuresOnlyBackOffAndNeverPark() throws Exception {
        // 源库不可达只说明"现在连不上"：没读到日志、也没写坏目标端，退避重连就是
        // 全部补救。旧实现把它和目标端 flush 失败记进同一个连续失败计数，一次约
        // 30 秒的源端抖动就把项目 park 到 ERROR，必须人工清错误才能恢复。
        when(dataSourceManager.getConnection(any()))
                .thenThrow(new SQLException("source down: connection refused"));

        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), boundedLock(20));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
    }

    @Test
    void sourceReadFailuresMidStreamOnlyBackOffAndNeverPark() throws Exception {
        // 源端在 drain 途中断流(防火墙空闲复位、网络抖动):这一轮没有读到变更,
        // 更没有向目标端写任何东西。旧实现把 drain 抛出的异常一律记为应用失败,
        // 连续 6 个这样的周期就把项目 park 到 ERROR —— 与"源端连不上只退避"的
        // 契约相违。源端读失败只能重连,不得累计 apply 熔断。
        PreparedStatement failingPeek = mock(PreparedStatement.class);
        when(failingPeek.executeQuery())
                .thenThrow(new SQLException("source stream reset"));
        when(source.prepareStatement(anyString())).thenReturn(failingPeek);

        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), boundedLock(20));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
    }

    @Test
    void aSuccessfulTransactionResetsTheFailureEscalation() throws Exception {
        // 3 败 → 1 成 → 3 败(之后保持失败):任一连续段都不到阈值,lock 到期安静
        // 退出,全程绝不 park;且成功周期的行必须真正落库(证明确实 flush 成功)。
        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(false, false, false, true, false, false, false),
                boundedLock(8));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
        assertThat(targetRowCount()).isEqualTo(1);
    }

    @Test
    void aHealthyIdleCycleResetsTheFailureEscalation() throws Exception {
        // 5 败 → 1 个无异常但 slot 里没有变更的空闲周期 → 5 败。计数器语义是
        // "连续失败周期",而旧实现只在事务落库时清零:被同步的表空闲时没有
        // flush,网络抖动留下的旧账一直累积,最终把健康项目 APPLY_STRIKE_OUT
        // 永久 park。空 peek 成功读通同样证明连接与解码通路健康。
        PreparedStatement ps = mock(PreparedStatement.class);
        AtomicInteger peeks = new AtomicInteger();
        when(ps.executeQuery()).thenAnswer(inv -> peeks.getAndIncrement() == 5
                ? emptyPeekResultSet() : oneTransactionResultSet());
        when(source.prepareStatement(anyString())).thenReturn(ps);

        PgLogicalCdcConsumer consumer = newConsumer(stagedTargetContext(false, false,
                false, false, false, false, false, false, false, false), boundedLock(11));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
    }

    @Test
    void aHealthyCycleLeavesLastErrorNullInsteadOfReportingDrained() throws Exception {
        // lastError 是"最近错误"通道:模板在非 null 时渲染成红色错误行。健康空闲
        // 轮询每秒发生 2 次(PG 默认 idle-poll 500ms),旧实现每轮写 "drained" ——
        // 每个正常运行的项目常驻一条红色"最近错误",且每轮 WARN 一行,空闲即把
        // 日志刷满(v1.7.7 修过的同一类问题)。契约:健康周期 lastError 保持 null,
        // 日志降到 DEBUG;只有失败周期才写 lastError 并 WARN。
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenAnswer(inv -> emptyPeekResultSet());
        when(source.prepareStatement(anyString())).thenReturn(ps);

        CdcRuntimeStats stats = new CdcRuntimeStats();
        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), boundedLock(3), stats);
        ListAppender<ILoggingEvent> logs = LogCapture.attach(PgLogicalCdcConsumer.class);
        try {
            consumer.run();
        } finally {
            LogCapture.detach(PgLogicalCdcConsumer.class, logs);
        }

        assertThat(stats.getLastError()).isNull();
        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .noneMatch(message -> message.contains("cycle for project"));
    }

    /** 空 peek:slot 里这段时间没有任何变更。 */
    private static ResultSet emptyPeekResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    /** BEGIN/message/COMMIT:一个只有 test_decoding 消息行的事务,没有任何可应用的行。 */
    private ResultSet messageOnlyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString(1)).thenReturn("0/2000", "0/2001", "0/2002");
        when(rs.getString(2)).thenReturn(
                "BEGIN 12345",
                "message: transactional: 1 prefix: hb, sz: 4 content: ping",
                "COMMIT 12345");
        return rs;
    }

    @Test
    void logicalMessageLinesFromOtherClientsDoNotParkTheProject() throws Exception {
        // 源库上任何客户端调用 pg_logical_emit_message(Debezium 心跳、应用自带的
        // 消息等),内容都会进入 jync 的 test_decoding slot —— slot 解码的是整个
        // 数据库,不是只有被同步的表。"message: ..." 既不是行事件,也永远不会被应用。
        //
        // 旧实现把它计进 parseFailures,而 parseFailures 只在成功 flush 一个事务时
        // 清零;被同步的表恰好空闲时没有任何 flush,6 条心跳就足以把一个完全健康的
        // 项目 park 成 PARSE_STRIKE_OUT,之后不再同步任何数据。
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenAnswer(inv -> messageOnlyResultSet());
        when(source.prepareStatement(anyString())).thenReturn(ps);

        PgLogicalCdcConsumer consumer = newConsumer(stagedTargetContext(true), boundedLock(8));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
    }

    /**
     * BEGIN + {@code updates} 条带 TOAST 占位符的 UPDATE + COMMIT:整段没有一个可应用的行,
     * 因此不会有 flush 来清零计数器 —— 正是会暴露错误累计的形状。
     */
    private ResultSet toastOnlyResultSet(int updates) throws SQLException {
        List<Boolean> next = new ArrayList<>();
        List<String> lsns = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        next.add(true);
        lsns.add("0/3000");
        lines.add("BEGIN 777");
        for (int i = 0; i < updates; i++) {
            next.add(true);
            lsns.add("0/30" + (10 + i));
            lines.add("table public.orders: UPDATE: id[bigint]:" + (i + 1)
                    + " name[text]:unchanged-toast-datum");
        }
        next.add(true);
        lsns.add("0/3999");
        lines.add("COMMIT 777");
        next.add(false);

        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(next.get(0),
                next.subList(1, next.size()).toArray(new Boolean[0]));
        when(rs.getString(1)).thenReturn(lsns.get(0),
                lsns.subList(1, lsns.size()).toArray(new String[0]));
        when(rs.getString(2)).thenReturn(lines.get(0),
                lines.subList(1, lines.size()).toArray(new String[0]));
        return rs;
    }

    @Test
    void changesWhoseValueIsNotInTheStreamDoNotParkTheProject() throws Exception {
        // REPLICA IDENTITY FULL 下未修改的 TOASTed 列在 test_decoding 里只剩一个
        // 占位符:值确实不在流里,行只能跳过 —— 设计上按表计数 + 限流告警(与
        // LogMiner 的行外 LOB 同口径),它不是解析缺陷。
        //
        // 旧实现仍把它计进 parseFailures,而计数器只在成功 flush 时清零:一张热表
        // 的 6 条连续更新,或一次没有其它可落行的事务,就足以把一个完全健康的项目
        // park 成 PARSE_STRIKE_OUT,之后整个项目不再同步 —— 与日志消息行那次事故
        // 同一条失效路径。
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenAnswer(inv -> toastOnlyResultSet(6));
        when(source.prepareStatement(anyString())).thenReturn(ps);

        CdcRuntimeStats stats = new CdcRuntimeStats();
        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(true), boundedLock(3), stats);

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
        // 跳过不是静默的:面板仍按表计数,运维能看到哪张表在丢值。
        assertThat(stats.missingLobByTable()).containsKey("public.orders");
    }

    // —— 状态面板真实性:轮询式消费者的 connected 必须粘性、吞吐窗口只标一次 ——

    @Test
    void aHealthyPollKeepsTheStatusPanelConnectedBetweenCycles() throws Exception {
        // 面板的 connected 圆点表示「这条 CDC 通路是否健康」。PG 消费者每轮 poll 才开
        // 一次源连接,旧实现每轮 finally 置 false → 面板在健康运行的绝大多数时间都显示
        // 红色「已断开」(5 秒刷新几乎不可能撞上几毫秒的连接窗口)。契约:上一轮成功后
        // 保持 connected,只有失败周期才落回 false。
        //
        // 采样点取在每轮开头的 lock.renew —— 面板就在这个时刻看 stats。
        CdcRuntimeStats stats = new CdcRuntimeStats();
        List<Boolean> panelAtCycleStart = new ArrayList<>();
        PgLogicalCdcConsumer consumer = newConsumer(
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
        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(false, true, true), samplingLock(stats, panelAtCycleStart, 3),
                stats);

        consumer.run();

        assertThat(panelAtCycleStart).containsExactly(false, false, true, true);
    }

    @Test
    void theThroughputWindowIsStampedOncePerRunNotPerPollCycle() throws Exception {
        // C3 吞吐:eventsPerSecond() = 自 markStarted 起的应用行数 ÷ 已过秒数。
        // 旧实现每轮 poll 都 markStarted,分子却是消费者启动以来的累计行数 —— 采样
        // 落在一轮开头显示 0.0,落在 1 秒之后则是一个永远在涨的天文数字。
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
        PgLogicalCdcConsumer consumer = newConsumer(
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
        project.setName("pg-1");
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new com.qqmu.jync.model.DatabaseConfig())
                .build();

        PgLogicalCdcConsumer consumer = new PgLogicalCdcConsumer(project, lock, ctx,
                positionStore, baselineService, taskStore, stateWriter, dataSourceManager,
                new CdcRuntimeStats(), properties);

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(ChangeType.ERROR), anyString(), anyBoolean(), anyLong());
        verify(lock).close();
    }
}
