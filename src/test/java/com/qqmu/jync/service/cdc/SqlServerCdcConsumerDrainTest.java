package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * P1-6:idle 早退不得饿死 capture instance 发现。
 *
 * <p>旧行为:maxLsn == fromLsn(源库无新变更)直接 return,根本不查
 * {@code cdc.change_tables}。DBA 给项目内新表启用 CDC 后、第一笔 DML 之前,
 * maxLsn 不会因它前进,consumer 每个 idle 周期都 hit 早退——新 CI 完全不可见,
 * 与 probe 的「已就绪」结论矛盾。修复:idle 时也按 {@code DISCOVERY_REFRESH_MS}
 * 节流强制重跑发现(每次 idle 轮询都查会白打系统表)。
 */
class SqlServerCdcConsumerDrainTest {

    private static final String LSN = "0x0000002857ae0258000a";

    private Connection conn;
    private PreparedStatement maxPs;
    private PreparedStatement discoveryPs;
    private SyncContext ctx;
    private CdcRuntimeStats stats;
    private SqlServerCdcConsumer consumer;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        maxPs = mock(PreparedStatement.class);
        discoveryPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("fn_cdc_get_max_lsn"))).thenReturn(maxPs);
        when(conn.prepareStatement(contains("cdc.change_tables"))).thenReturn(discoveryPs);

        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenReturn(LSN);
        when(maxPs.executeQuery()).thenReturn(maxRs);

        ResultSet discoveryRs = mock(ResultSet.class);
        when(discoveryRs.next()).thenReturn(false);
        when(discoveryPs.executeQuery()).thenReturn(discoveryRs);

        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-1");
        ctx = mock(SyncContext.class);
        when(ctx.getConfig()).thenReturn(new com.qqmu.jync.dto.SyncConfig());
        stats = new CdcRuntimeStats();
        consumer = new SqlServerCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), ctx,
                mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class),
                mock(DataSourceManager.class), stats, new SyncProperties());
    }

    @Test
    void idleDrainRunsDiscoveryWhenRefreshIsDue() throws Exception {
        // maxLsn == fromLsn → idle;但发现节流窗口已过(新 consumer,从未发现过)
        // → 必须仍然查询 cdc.change_tables。
        consumer.drain(conn, LSN);

        verify(discoveryPs).executeQuery();
    }

    @Test
    void idleDrainSkipsDiscoveryWithinRefreshWindow() throws Exception {
        // 第一次 idle 轮询消耗了「到期」;紧随其后的第二次必须跳过发现
        // (1s idle 轮询不允许每次都打系统表)。
        consumer.drain(conn, LSN);
        consumer.drain(conn, LSN);

        verify(discoveryPs, times(1)).executeQuery();
    }

    @Test
    void idleDrainAdvancesNothing() throws Exception {
        // idle 语义不变:不产行、不动水位。
        SqlServerCdcConsumer.DrainResult result = consumer.drain(conn, LSN);
        assertThat(result.rows).isZero();
        assertThat(result.nextLsn).isNull();
    }

    /**
     * 源库被还原/回滚到更早的时间点(或项目改指向另一台源库)时,源端当前的
     * {@code fn_cdc_get_max_lsn()} 会落在持久化水位「之后」。过去没有任何分支处理这种情形:
     * 倒置区间 (from, max] 被 SQL Server 以 Msg 313 拒绝,而 applyFailed 标记只在
     * flushTransaction 里置位,于是它被归为 TRANSIENT —— 每轮重试同一个注定失败的区间,
     * 永远不 park、也永远不推进水位(水位之上的行在源端已不存在,不重基线不可能补回)。
     */
    @Test
    void aSourceBehindTheStoredWatermarkParksAsPurged() throws Exception {
        ResultSet behindRs = mock(ResultSet.class);
        when(behindRs.next()).thenReturn(true);
        when(behindRs.getString(1)).thenReturn("0x00000000000000000001");
        when(maxPs.executeQuery()).thenReturn(behindRs);

        Throwable thrown = catchThrowable(() -> consumer.drain(conn, LSN));

        assertThat(thrown).isInstanceOf(SQLException.class);
        assertThat(CdcFailureClassifier.classify(thrown))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        // 判定就在读 max LSN 之后,不需要再问 cdc.change_tables(也没必要再拉变更)。
        verify(discoveryPs, never()).executeQuery();
    }

    @Test
    void anEmptyCaptureInstanceDrainCountsNoSkippedChange() throws Exception {
        // No capture instance is a configuration state, not a change that was skipped: the
        // old code added 1 to "skippedUnsupported" on every poll, so an idle project with no
        // CI yet (or one waiting for the DBA) made the user-visible counter climb forever
        // while nothing was actually being skipped.
        consumer.drain(conn, "0x00000000000000000000");

        assertThat(stats.snapshot().get("skippedUnsupported")).isEqualTo(0L);
    }

    @Test
    void aCaptureInstanceDroppedMidStreamIsWarnedAbout() throws Exception {
        // A DBA running sp_cdc_disable_table takes the instance out of cdc.change_tables.
        // The consumer holds its watermark — correctly — but used to say nothing at all, so
        // the project simply stopped capturing with no signal anywhere in the log.
        ResultSet withCi = mock(ResultSet.class);
        when(withCi.next()).thenReturn(true, false);
        when(withCi.getString(1)).thenReturn("dbo");
        when(withCi.getString(2)).thenReturn("orders");
        when(withCi.getString(3)).thenReturn("dbo_orders");
        when(withCi.getString(4)).thenReturn("0x00000029000000000000");
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(discoveryPs.executeQuery()).thenReturn(withCi, empty);

        PreparedStatement changes = mock(PreparedStatement.class);
        ResultSet noRows = mock(ResultSet.class);
        when(noRows.next()).thenReturn(false);
        when(changes.executeQuery()).thenReturn(noRows);
        when(conn.prepareStatement(contains("fn_cdc_get_all_changes"))).thenReturn(changes);

        String from = "0x00000000000000000000";
        consumer.drain(conn, from); // picks up dbo_orders

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SqlServerCdcConsumer.class);
        try {
            consumer.drain(conn, from); // the instance is gone
        } finally {
            LogCapture.detach(SqlServerCdcConsumer.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.WARN, logs)).anySatisfy(message ->
                assertThat(message).contains("dbo_orders").contains("no longer"));
    }
}
