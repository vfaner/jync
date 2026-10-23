package com.qqmu.jync.service.cdc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.ConnectionProvider;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * F-13(代码审查对照 F-10/F-12):SQLServer 消费者<b>没有任何生效的 strike-out</b>。
 * parseFailures 字段被声明、在 flushTransaction 成功时清零、在 parseStrikeOut() 里
 * 检查,但全程<b>没有一处自增</b>(SQLServer 读类型化列,不像 Oracle 逐行解析
 * SQL_REDO,根本没有"解析失败"计数点)——parseStrikeOut() 恒为 false;又没有
 * applyFailures。于是目标端 flush 被拒(缺列 SQLException,分类器归 TRANSIENT)时,
 * run() 的升级判定 {@code kind != TRANSIENT || parseStrikeOut()} 恒为 false,
 * 消费者<b>无限空转</b>:既不 park ERROR 也不前进,每个 idle 周期开/回滚/关一次目标
 * 连接,直到进程被杀。与 F-10(PG)/F-12(Oracle)同款,只因 SQLServer 无法 docker
 * 实测而漏网。SqlServerCdcConsumerFlushTest 的注释甚至把"strike-out 把项目 park"
 * 当作既有兜底来描述,而它根本不会触发。
 *
 * <p>契约(本测试钉住,与 F-10/F-12 同阈值同语义):
 * <ul>
 *   <li>连续 {@code > 5} 个失败周期(无成功事务夹在中间)→ recordCdcFailure +
 *       park ERROR,标签 APPLY_STRIKE_OUT,线程自行结束;</li>
 *   <li>一个成功落库的事务必须把连续 apply 失败计数清零 —— 3 败 + 1 成 + 3 败
 *       不到阈值,不得被老账 park。</li>
 * </ul>
 */
class SqlServerCdcConsumerFailureEscalationTest {

    private static final String FROM_LSN = "0x00000000000000000000";
    private static final String TX_LSN = "0x0000002857ae025800a0";

    private Connection sourceConn;
    private Connection targetConn;
    private PreparedStatement targetPs;
    private MetadataReader reader;
    private CdcPositionStore positionStore;
    private SyncTaskStore taskStore;
    private DataSourceManager dsm;
    private SyncProperties properties;
    private final AtomicInteger maxLsnCycle = new AtomicInteger();
    private final AtomicInteger targetCycle = new AtomicInteger();

    /** 每周期递增的 max_lsn:成功周期把水位推到 maxLsn 后,下周期 maxLsn 更大,
     *  不会落入 idle 早返回,从而能继续编排失败周期。 */
    private String maxLsnForCycle() {
        return "0x0000002857ae0258" + String.format("%04x", maxLsnCycle.getAndIncrement());
    }

    @BeforeEach
    void setUp() throws SQLException {
        sourceConn = mock(Connection.class);
        targetConn = mock(Connection.class);
        targetPs = mock(PreparedStatement.class);
        when(targetConn.prepareStatement(anyString())).thenReturn(targetPs);
        when(targetConn.getAutoCommit()).thenReturn(true);

        // —— 源库三路分发 ——
        PreparedStatement maxPs = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenAnswer(inv -> maxLsnForCycle());
        when(maxPs.executeQuery()).thenReturn(maxRs);
        when(sourceConn.prepareStatement(contains("fn_cdc_get_max_lsn"))).thenReturn(maxPs);

        PreparedStatement discoveryPs = mock(PreparedStatement.class);
        when(discoveryPs.executeQuery()).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn("dbo");
            when(rs.getString(2)).thenReturn("orders");
            when(rs.getString(3)).thenReturn("dbo_orders");
            return rs;
        });
        when(sourceConn.prepareStatement(contains("cdc.change_tables"))).thenReturn(discoveryPs);

        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(changesPs.executeQuery()).thenAnswer(inv -> oneInsertChangesRs());
        when(sourceConn.prepareStatement(contains("fn_cdc_get_all_changes_")))
                .thenReturn(changesPs);

        // —— 元数据:dbo.orders 单列 ID(PK) ——
        reader = mock(MetadataReader.class);
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.setSchema("dbo");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(java.sql.Types.INTEGER);
        id.setPrimaryKey(true);
        meta.setColumns(new ArrayList<>(List.of(id)));
        meta.setPrimaryKeys(new ArrayList<>(List.of("ID")));
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);

        dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenReturn(sourceConn);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = new CdcPosition();
        anchored.setProjectId(1L);
        anchored.setLsn(FROM_LSN);
        anchored.setBaselineDone(true);
        when(positionStore.find(1L)).thenReturn(Optional.of(anchored));

        taskStore = mock(SyncTaskStore.class);
        properties = new SyncProperties();
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getMssql().setIdlePollMs(1);
    }

    /** One INSERT row in one transaction — the shape every failing cycle re-reads. */
    private static ResultSet oneInsertChangesRs() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(3);
        when(md.getColumnName(1)).thenReturn("__$start_lsn");
        when(md.getColumnName(2)).thenReturn("__$operation");
        when(md.getColumnName(3)).thenReturn("ID");
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(TX_LSN);
        when(rs.getInt(2)).thenReturn(2); // INSERT
        when(rs.getObject("ID")).thenReturn(1);
        return rs;
    }

    /** A captured-but-empty window: the capture job has not written a row this cycle. */
    private static ResultSet emptyChangesRs() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(0);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private SyncContext contextWithStagedTarget(final boolean... cycleSucceeds) throws SQLException {
        SyncContext ctx = mock(SyncContext.class);
        SyncConfig config = mock(SyncConfig.class);
        when(config.includesTable("orders")).thenReturn(true);
        when(config.targetTableName("orders")).thenReturn("orders");
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getSourceReader()).thenReturn(reader);
        when(ctx.getSourceConfig()).thenReturn(new DatabaseConfig());
        when(ctx.getTargetConfig()).thenReturn(new DatabaseConfig());
        SqlDialect dialect = mock(SqlDialect.class);
        when(dialect.getUpsertSql(any(), any(), any(), any()))
                .thenReturn("MERGE INTO orders ...");
        when(dialect.getUpsertSql(any(), any(), any(), any(), any()))
                .thenReturn("MERGE INTO orders ...");
        when(dialect.upsertBindOrder(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getTargetDialect()).thenReturn(dialect);
        when(ctx.getBatchSize()).thenReturn(100);
        ConnectionProvider provider = mock(ConnectionProvider.class);
        when(provider.open(any())).thenAnswer(inv -> {
            int n = targetCycle.getAndIncrement();
            boolean ok = n < cycleSucceeds.length
                    ? cycleSucceeds[n]
                    : cycleSucceeds[cycleSucceeds.length - 1];
            if (ok) {
                return targetConn;
            }
            throw new SQLException("Invalid column name 'dropped_col'");
        });
        when(ctx.getConnectionProvider()).thenReturn(provider);
        return ctx;
    }

    private SqlServerCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock) {
        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-1");
        return new SqlServerCdcConsumer(project, lock, ctx, positionStore,
                mock(CdcBaselineService.class), taskStore,
                mock(SyncStateWriter.class), dsm, new CdcRuntimeStats(), properties);
    }

    /** Lock renews for {@code grants} cycles, then refuses — bounds the loop in every path. */
    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }

    @Test
    void repeatedTargetFlushFailuresEscalateToAnErrorPark() throws Exception {
        // 目标每轮拒绝 → drain 每轮抛 TRANSIENT。第 6 个连续失败周期必须升级
        // (recordCdcFailure + park APPLY_STRIKE_OUT),run() 自行结束,
        // 而不是靠 lock 失效安静退出。
        SqlServerCdcConsumer consumer = newConsumer(
                contextWithStagedTarget(false), boundedLock(20));

        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L), contains("Invalid column name"));
        verify(taskStore).recordCdcFailure(eq(1L), contains("APPLY_STRIKE_OUT"));
    }

    @Test
    void aSuccessfulTransactionResetsTheFailureEscalation() throws Exception {
        // 3 败 → 1 成 → 3 败:任一连续段都不到阈值,lock 到期安静退出,全程绝不
        // park;且成功周期必须真正 commit(证明确实 flush 成功、计数被清零)。
        SqlServerCdcConsumer consumer = newConsumer(
                contextWithStagedTarget(false, false, false, true, false, false, false),
                boundedLock(7));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
        verify(targetConn, times(1)).commit();
    }

    @Test
    void aHealthyIdleCycleResetsTheFailureEscalation() throws Exception {
        // 5 败 → 1 个无异常但 0 行的空闲周期 → 5 败。计数器语义是"连续失败周期",
        // 而旧实现只在事务落库时清零:被同步的表恰好空闲时没有任何 flush,网络抖动
        // 留下的旧账会一直累积,最终把一个完全健康的项目 APPLY_STRIKE_OUT 永久 park。
        // 空窗口成功读通证明连接与读取通路健康,必须清零。
        PreparedStatement idleCapable = mock(PreparedStatement.class);
        AtomicInteger drains = new AtomicInteger();
        when(idleCapable.executeQuery()).thenAnswer(inv -> drains.getAndIncrement() == 5
                ? emptyChangesRs() : oneInsertChangesRs());
        when(sourceConn.prepareStatement(contains("fn_cdc_get_all_changes_")))
                .thenReturn(idleCapable);

        SqlServerCdcConsumer consumer = newConsumer(
                contextWithStagedTarget(false, false, false, false, false, false, false,
                        false, false, false),
                boundedLock(11));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
    }

    @Test
    void baselineLeaseLossExitsQuietlyWithoutError() throws Exception {
        // 基线全量期间租约被接管:runBaseline 抛 LockLostException。R1 要求安静退出 ——
        // 不得 recordCdcFailure / 写 ERROR 行(接管节点可能已把状态标成 RUNNING)。
        when(positionStore.find(1L)).thenReturn(Optional.empty());
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        doThrow(new LockLostException("lease lost mid-baseline"))
                .when(baselineService).runBaseline(any(), any());
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(false);
        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-1");
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new DatabaseConfig())
                .build();

        SqlServerCdcConsumer consumer = new SqlServerCdcConsumer(project, lock, ctx,
                positionStore, baselineService, taskStore, stateWriter, dsm,
                new CdcRuntimeStats(), properties);

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(ChangeType.ERROR), anyString(), anyBoolean(), anyLong());
        verify(lock).close();
    }
}
