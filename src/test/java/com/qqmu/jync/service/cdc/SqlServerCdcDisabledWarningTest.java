package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * #4(可观测性):{@code sys.fn_cdc_get_max_lsn()} 返回 NULL 当且仅当 CDC 在库级被
 * 关闭。旧代码 drain() 在 {@code maxLsn == null} 时静默 {@code return out} —— DBA
 * 中途关闭 CDC 后,消费者每个 idle 周期空转、既不 warn 也不 park,用户得不到任何
 * 信号(与"静默卡死"同类)。修复:{@code maxLsn == null} 时发一条节流 WARN(每
 * {@link SqlServerCdcConsumer#DISCOVERY_REFRESH_MS} 至多一次,避免 1s idle poll 刷屏),
 * 不 park —— 重新启用 CDC 后自动恢复。
 *
 * <p>本测试钉住:① CDC 关闭的周期必须发出含 "disabled" 的 WARN(真实可观测行为,
 * 修复点就是这条日志);② 绝不 park(recordCdcFailure never)。
 */
class SqlServerCdcDisabledWarningTest {

    private static final String FROM_LSN = "0x00000000000000000000";

    @Test
    void disabledSourceCdcEmitsAVisibleWarningAndDoesNotPark() throws SQLException {
        // fn_cdc_get_max_lsn() == NULL + is_cdc_enabled=0 → CDC 库级关闭。drain() 在
        // readMaxLsn 早返回,不触及 discovery/changes/target,故只需 maxLsn 这一路 mock。
        Run run = runOneCycle(Boolean.FALSE);

        assertThat(run.events).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage()).contains("disabled");
        });
        // 启动行与停机行配对,重启不再看起来像崩溃。
        verify(run.stateWriter).recordLog(1L, ObjectType.PROJECT, "mssql-cdc-off",
                ChangeType.INFO, "SQLServer consumer started", true, 0L);
        verify(run.taskStore, never()).recordCdcFailure(anyLong(), anyString());
    }

    /**
     * 实证(SQL Server 2022 容器):库级 CDC 已启用、capture 实例已建好,但只要 capture
     * 作业还没处理过任何事务,max_lsn 就是 NULL。旧代码把这条 NULL 一律说成「CDC 已在
     * 库级关闭」,会把 DBA 引向一次没必要的 sp_cdc_enable_db;真正的线索是 Agent 没跑。
     */
    @Test
    void enabledButNothingCapturedYetWarnsAboutTheAgentNotAboutDisabledCdc()
            throws SQLException {
        Run run = runOneCycle(Boolean.TRUE);

        assertThat(run.events).anySatisfy(ev -> {
            assertThat(ev.getLevel()).isEqualTo(Level.WARN);
            assertThat(ev.getFormattedMessage())
                    .contains("Agent")
                    .doesNotContain("disabled at the database level");
        });
        verify(run.taskStore, never()).recordCdcFailure(anyLong(), anyString());
    }

    /** One consumer run against a source whose fn_cdc_get_max_lsn() is NULL. */
    private static final class Run {
        final List<ILoggingEvent> events;
        final SyncStateWriter stateWriter;
        final SyncTaskStore taskStore;

        Run(List<ILoggingEvent> events, SyncStateWriter stateWriter, SyncTaskStore taskStore) {
            this.events = events;
            this.stateWriter = stateWriter;
            this.taskStore = taskStore;
        }
    }

    /** @param cdcEnabled the stubbed sys.databases.is_cdc_enabled value. */
    private Run runOneCycle(Boolean cdcEnabled) throws SQLException {
        Connection sourceConn = mock(Connection.class);
        PreparedStatement maxPs = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenReturn(null);
        when(maxPs.executeQuery()).thenReturn(maxRs);
        when(sourceConn.prepareStatement(contains("fn_cdc_get_max_lsn"))).thenReturn(maxPs);
        Statement probe = mock(Statement.class);
        ResultSet enabledRs = mock(ResultSet.class);
        when(enabledRs.next()).thenReturn(true);
        when(enabledRs.getBoolean(1)).thenReturn(cdcEnabled);
        when(probe.executeQuery(contains("is_cdc_enabled"))).thenReturn(enabledRs);
        when(sourceConn.createStatement()).thenReturn(probe);

        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenReturn(sourceConn);

        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = new CdcPosition();
        anchored.setProjectId(1L);
        anchored.setLsn(FROM_LSN);
        anchored.setBaselineDone(true);
        when(positionStore.find(1L)).thenReturn(Optional.of(anchored));

        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncProperties properties = new SyncProperties();
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getMssql().setIdlePollMs(1);

        SyncContext ctx = mock(SyncContext.class);
        when(ctx.getSourceConfig()).thenReturn(new DatabaseConfig());
        // run() 启动时要解析项目级空闲轮询覆盖(留空=引擎默认);真实 ctx 必带 config。
        when(ctx.getConfig()).thenReturn(new com.qqmu.jync.dto.SyncConfig());

        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-cdc-off");

        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        SqlServerCdcConsumer consumer = new SqlServerCdcConsumer(project, boundedLock(1), ctx,
                positionStore, mock(CdcBaselineService.class), taskStore,
                stateWriter, dsm, new CdcRuntimeStats(), properties);

        ListAppender<ILoggingEvent> appender = LogCapture.attach(SqlServerCdcConsumer.class);
        try {
            consumer.run();
        } finally {
            LogCapture.detach(SqlServerCdcConsumer.class, appender);
        }
        return new Run(new java.util.ArrayList<>(appender.list), stateWriter, taskStore);
    }

    /** Lock renews for {@code grants} cycles, then refuses — bounds the loop in every path. */
    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }
}
