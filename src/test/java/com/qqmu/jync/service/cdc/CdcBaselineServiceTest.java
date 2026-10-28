package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncTaskStore;
import com.qqmu.jync.util.CryptoUtil;

class CdcBaselineServiceTest {

    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final SyncEngine engine = mock(SyncEngine.class);
    private final CdcPositionStore positionStore = mock(CdcPositionStore.class);
    private final SyncTaskStore taskStore = mock(SyncTaskStore.class);
    private final CryptoUtil crypto = new CryptoUtil(props());   // @Component,构造吃 SyncProperties(默认 key/salt)
    private final BinlogClientFactory clientFactory =
            new BinlogClientFactory(crypto, props());
    private final CdcBaselineService service = new CdcBaselineService(
            positionStore, clientFactory, taskStore, dataSourceManager, engine);

    private static com.qqmu.jync.config.SyncProperties props() {
        return new com.qqmu.jync.config.SyncProperties();
    }

    /** SHOW BINARY LOG STATUS 可用(8.4+)、gtid_mode=ON 的源库。 */
    private Connection stubSource(String file, long pos, String gtidMode, String gtidExecuted)
            throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(any(String.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("SHOW BINARY LOG STATUS") || sql.contains("SHOW MASTER STATUS")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString("File")).thenReturn(file);
                when(rs.getLong("Position")).thenReturn(pos);
            } else if (sql.contains("gtid_mode")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn(gtidMode);
            } else if (sql.contains("gtid_executed")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn(gtidExecuted);
            }
            return rs;
        });
        return conn;
    }

    private DatabaseConfig source() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        config.setHost("db.local");
        config.setPort(3306);
        config.setUsername("repl");
        config.setPassword(crypto.encrypt("secret"));
        return config;
    }

    private SyncContext ctx(long projectId) {
        com.qqmu.jync.model.Project project = new com.qqmu.jync.model.Project();
        project.setId(projectId);
        project.setName("cdc-test");
        // Project 实体没有 sourceConfig 字段(只有 sourceDbId 外键);ctx 的 sourceConfig
        // 由 SyncContextFactory 解析注入,测试直接塞 builder
        return SyncContext.builder().project(project).sourceConfig(source()).build();
    }

    private SyncContext logMinerCtx(long projectId) {
        com.qqmu.jync.model.Project project = new com.qqmu.jync.model.Project();
        project.setId(projectId);
        project.setName("lm-test");
        com.qqmu.jync.dto.SyncConfig cfg = new com.qqmu.jync.dto.SyncConfig();
        cfg.setCaptureStrategy(com.qqmu.jync.model.CaptureStrategy.LOGMINER_CDC);
        return SyncContext.builder().project(project).sourceConfig(source()).config(cfg).build();
    }

    @Test
    void readsPositionFromModernStatement() throws SQLException {
        // stubSource 先于 when(...) 求值:它自己也在打桩,塞进 thenReturn 实参位会撞上
        // Mockito 的 UnfinishedStubbing(嵌套打桩),语义不变、只求值顺序提前。
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "ON", "uuid:1-9");
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        CdcPositionMark mark = service.readCurrentPosition(source());
        assertThat(mark.getFile()).isEqualTo("mysql-bin.000003");
        assertThat(mark.getPos()).isEqualTo(154L);
        assertThat(mark.getGtidSet()).isEqualTo("uuid:1-9");
    }

    @Test
    void fallsBackToMasterStatusWhenModernMissing() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery("SHOW BINARY LOG STATUS"))
                .thenThrow(new SQLException("unknown statement"));
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery("SHOW MASTER STATUS")).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("File")).thenReturn("mysql-bin.000001");
        when(rs.getLong("Position")).thenReturn(4L);
        ResultSet gtidOff = mock(ResultSet.class);
        when(st.executeQuery("SELECT @@GLOBAL.gtid_mode")).thenReturn(gtidOff);
        when(gtidOff.next()).thenReturn(true, false);
        when(gtidOff.getString(1)).thenReturn("OFF");
        when(dataSourceManager.getConnection(any())).thenReturn(conn);

        CdcPositionMark mark = service.readCurrentPosition(source());
        assertThat(mark.getFile()).isEqualTo("mysql-bin.000001");
        assertThat(mark.getPos()).isEqualTo(4L);
        assertThat(mark.getGtidSet()).isNull();
    }

    @Test
    void baselineSequenceIsPositionResetRunMarkDone() throws SQLException {
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "OFF", null);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        // 真实 runCycle 不抛异常、把失败折进 result:成功的基线必须显式给出 success 结果
        when(engine.runCycle(any(SyncContext.class))).thenReturn(new SyncResult());

        service.runBaseline(ctx(7L), () -> true);

        InOrder order = inOrder(positionStore, engine);
        order.verify(positionStore).recordBaselineStart(eq(7L), eq("mysql-bin.000003"),
                eq(154L), org.mockito.ArgumentMatchers.isNull(), anyLong());
        order.verify(engine).resetProject(7L);
        order.verify(engine).runCycle(any(SyncContext.class));
        order.verify(positionStore).markBaselineDone(7L);
        // 关键顺序:位点先落库再全量——全量期间发生的变更不会漏(B4)
    }

    /**
     * 基线重新加载的是「目标端此刻的样子」,进程内那些「这张表已经核对过/刚审计过」的
     * 结论必须一起作废。只调 stateWriter.resetProject 清掉库里的进度与快照时,验证通过
     * 的缓存(verifiedTables)会活过基线:目标表在基线前被手工重建/换 schema,全量却
     * 跳过形状复核,把旧结论套到新表上,数据阶段才开始逐行报错。
     */
    @Test
    void baselineEvictsTheEngineInMemoryVerdictsToo() throws SQLException {
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "OFF", null);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        when(engine.runCycle(any(SyncContext.class))).thenReturn(new SyncResult());

        service.runBaseline(ctx(7L), () -> true);

        verify(engine).resetProject(7L);
    }

    @Test
    void failureRecordsErrorAndDeletesHalfWrittenPosition() throws SQLException {
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "OFF", null);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        doThrow(new IllegalStateException("full load exploded"))
                .when(engine).runCycle(any(SyncContext.class));

        assertThatThrownBy(() -> service.runBaseline(ctx(7L), () -> true))
                .isInstanceOf(IllegalStateException.class);
        verify(positionStore).delete(7L);                       // 残缺位点必须清掉
        verify(positionStore, never()).markBaselineDone(anyLong());
        verify(taskStore).recordCdcFailure(eq(7L), org.mockito.ArgumentMatchers.contains("exploded"));
    }

    @Test
    void failedFullLoadDoesNotMarkBaselineDone() throws SQLException {
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "OFF", null);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        // runCycle 把 SQLException/LockLost/RuntimeException 折进 result 而不外抛
        // (SyncEngine.java:144-159):失败的全量必须按失败处理,否则残缺快照 +
        // baseline_done=true,流从记下的位点回放,漏掉的行永久丢失(任务书 B4:
        // 全量失败整体重来)。
        SyncResult failed = new SyncResult();
        failed.addError("lease lost mid-load");
        when(engine.runCycle(any(SyncContext.class))).thenReturn(failed);

        assertThatThrownBy(() -> service.runBaseline(ctx(7L), () -> true))
                .isInstanceOf(IllegalStateException.class);
        verify(positionStore).delete(7L);                       // 残缺位点必须清掉
        verify(positionStore, never()).markBaselineDone(anyLong());
        verify(taskStore).recordCdcFailure(eq(7L),
                org.mockito.ArgumentMatchers.contains("lease lost"));
    }

    /**
     * R1(租约丢失):基线全量期间租约被他节点接管时,引擎把 {@link LockLostException}
     * 折进失败 result。旧代码按普通失败处理 —— 删掉位点行(可能正砸掉接管节点刚写的
     * 基线锚点)并按 ERROR 帽子停摆(所有节点的 supervise 因 ERROR-skip 冻结)。租约
     * 已丢 = 本节点无权收尾:安静退出,位点行留给接管节点,基线由它重跑。
     */
    @Test
    void leaseLostMidBaselineLeavesThePositionRowAndSkipsTheErrorHat() throws SQLException {
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "OFF", null);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        SyncResult failed = new SyncResult();
        failed.addError("Sync lock lost mid-run (lease expired or reclaimed by another instance)");
        when(engine.runCycle(any(SyncContext.class))).thenReturn(failed);

        assertThatThrownBy(() -> service.runBaseline(ctx(7L), () -> false))  // renew 返回 false
                .isInstanceOf(LockLostException.class);
        verify(positionStore, never()).delete(anyLong());        // 不砸接管节点的锚点
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());  // 绝不 ERROR 帽子
        verify(positionStore, never()).markBaselineDone(anyLong());     // 基线绝不算完成
    }

    /**
     * 终审 FIX 2:stop-requested 中止 ≠ 失败。分块全量在 chunk 边界被 interrupt 中止后,
     * SyncEngine 把它折成【失败 result】(runBaseline 看到的世界与真失败相同)——唯一的
     * 区分依据是顺序循环刻意保留的中断位。中止时:不摘 ERROR 帽子(recordCdcFailure 会把
     * 项目在所有节点的 supervise 上冻死)、不删位点行(它保持 baseline_done=false,下次
     * 启动整体重跑;rebaseline-竞态下新消费者刚写的行也绝不会被旧线程退出时删掉)。
     */
    @Test
    void stopRequestedAbortKeepsBaselinePendingAndSkipsTheErrorHat() throws SQLException {
        Connection sourceConn = stubSource("mysql-bin.000003", 154L, "OFF", null);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);
        SyncResult aborted = new SyncResult();
        aborted.addError("Full load of ITEMS aborted at chunk 3: stop requested"
                + " (thread interrupted)");
        when(engine.runCycle(any(SyncContext.class))).thenReturn(aborted);

        Thread.currentThread().interrupt();   // 顺序循环退出时刻意保留的中断位
        try {
            assertThatThrownBy(() -> service.runBaseline(ctx(7L), () -> true))
                    .isInstanceOf(IllegalStateException.class);
            verify(taskStore, never()).recordCdcFailure(anyLong(), any());  // 绝不 ERROR 帽子
            verify(positionStore, never()).delete(anyLong());               // 位点行保持 not-done
            verify(positionStore, never()).markBaselineDone(anyLong());     // 基线绝不算完成
        } finally {
            Thread.interrupted();             // 清掉,别影响本线程上的后续测试
        }
    }

    // —— v1.6.0 LogMiner SCN 分支 ——

    @Test
    void logMinerBaselineRecordsScnInsteadOfBinlogPosition() throws SQLException {
        // Oracle 源:CURRENT_SCN 是基线锚点;binlog SHOW 语句在 Oracle 上不存在。
        Connection oracle = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(oracle.createStatement()).thenReturn(st);
        ResultSet scnRs = mock(ResultSet.class);
        when(st.executeQuery("SELECT CURRENT_SCN FROM V$DATABASE")).thenReturn(scnRs);
        when(scnRs.next()).thenReturn(true);
        when(scnRs.getLong(1)).thenReturn(77777L);
        when(dataSourceManager.getConnection(any())).thenReturn(oracle);
        when(engine.runCycle(any(SyncContext.class))).thenReturn(new SyncResult());

        service.runBaseline(logMinerCtx(12L), () -> true);

        // 关键差异:recordBaselineStartScn(而不是 recordBaselineStart),binlog serverId 不读。
        verify(positionStore).recordBaselineStartScn(12L, 77777L);
        verify(positionStore, never()).recordBaselineStart(anyLong(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), anyLong());
        verify(engine).resetProject(12L);
        verify(positionStore).markBaselineDone(12L);
    }

    // —— SQL Server LSN 锚点 ——

    /**
     * 实证(SQL Server 2022 容器):库级 CDC 已启用、表 capture 实例也建好,但只要 capture
     * 作业还没处理过任何事务,{@code sys.fn_cdc_get_max_lsn()} 就返回 NULL —— 这不是
     * 「CDC 未启用」。旧代码据此直接抛异常:消费者戴上 ERROR 帽停机,只能人工 rebaseline,
     * 而启用后暂时没有写入的小表会一直停在这里。此刻库里没有任何已捕获变更,锚定 0 等价于
     * 「从此刻开始」:回放不可能早于任何真实事件。
     */
    @Test
    void sqlServerBaselineAnchorsAtZeroWhenCdcIsEnabledButNothingWasCapturedYet()
            throws SQLException {
        Connection mssql = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(mssql.createStatement()).thenReturn(st);
        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenReturn(null);
        when(st.executeQuery(contains("fn_cdc_get_max_lsn"))).thenReturn(maxRs);
        ResultSet enabledRs = mock(ResultSet.class);
        when(enabledRs.next()).thenReturn(true);
        when(enabledRs.getBoolean(1)).thenReturn(true);
        when(st.executeQuery(contains("is_cdc_enabled"))).thenReturn(enabledRs);
        when(dataSourceManager.getConnection(any())).thenReturn(mssql);

        assertThat(service.readCurrentLsnSqlServer(source()))
                .isEqualTo("0x00000000000000000000");
    }

    @Test
    void sqlServerBaselineStillFailsWhenCdcIsGenuinelyDisabled() throws SQLException {
        // 区分两种 NULL:库级 is_cdc_enabled=0 是真关闭(基线必须失败并说清原因),
        // 已启用只是还没有已捕获变更(上一条测试)才锚定 0。
        Connection mssql = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(mssql.createStatement()).thenReturn(st);
        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenReturn(null);
        when(st.executeQuery(contains("fn_cdc_get_max_lsn"))).thenReturn(maxRs);
        ResultSet enabledRs = mock(ResultSet.class);
        when(enabledRs.next()).thenReturn(true);
        when(enabledRs.getBoolean(1)).thenReturn(false);
        when(st.executeQuery(contains("is_cdc_enabled"))).thenReturn(enabledRs);
        when(dataSourceManager.getConnection(any())).thenReturn(mssql);

        assertThatThrownBy(() -> service.readCurrentLsnSqlServer(source()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is_cdc_enabled=0");
    }

    // —— v1.9.0:PG 家族基线锚点 ——

    /**
     * openGauss 5.0.0(docker 实测)没有 {@code pg_current_wal_lsn()}——它源自 PG 9.2,只有
     * {@code pg_current_xlog_location()};产品名仍是 "PostgreSQL",靠 version() 横幅识别。
     * 基线锚点取错会直接让 PG_LOGICAL_CDC 启动即失败。
     */
    @Test
    void readCurrentLsnPgUsesTheOpenGaussFunctionWhenTheServerIsOpenGauss() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        // 目录读不到 → 退回横幅判定,这条用例钉的就是退回后的那条路。
        when(st.executeQuery(contains("pg_type")))
                .thenThrow(new SQLException("catalog is not readable"));
        ResultSet banner = mock(ResultSet.class);
        when(banner.next()).thenReturn(true, false);
        when(banner.getString(1)).thenReturn("(openGauss 5.0.0 build ) compiled at"
                + " 2023-04-18 10:27:05 commit 0 last mr  on aarch64-unknown-linux-gnu");
        when(st.executeQuery(contains("version()"))).thenReturn(banner);
        ResultSet lsn = mock(ResultSet.class);
        when(lsn.next()).thenReturn(true, false);
        when(lsn.getString(1)).thenReturn("0/D890C70");
        when(st.executeQuery("SELECT pg_current_xlog_location()::text")).thenReturn(lsn);
        when(dataSourceManager.getConnection(any())).thenReturn(conn);

        assertThat(service.readCurrentLsnPg(source())).isEqualTo("0/D890C70");
        verify(st, never()).executeQuery("SELECT pg_current_wal_lsn()::text");
    }

    @Test
    void readCurrentLsnPgKeepsTheWalLsnFunctionOnVanillaPostgres() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        // 能力判定先跑:pg_lsn 与 pg_current_wal_lsn 都在 → 原生 POSTGRES 分支。
        ResultSet catalog = mock(ResultSet.class);
        when(catalog.next()).thenReturn(true, false);
        when(catalog.getLong(1)).thenReturn(1L);
        when(catalog.getLong(2)).thenReturn(1L);
        when(st.executeQuery(contains("pg_type"))).thenReturn(catalog);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("0/16B3748");
        when(st.executeQuery("SELECT pg_current_wal_lsn()::text")).thenReturn(rs);
        when(dataSourceManager.getConnection(any())).thenReturn(conn);

        assertThat(service.readCurrentLsnPg(source())).isEqualTo("0/16B3748");
    }

    @Test
    void readCurrentScnSurfacesSqlExceptionsAsIllegalState() throws SQLException {
        Connection oracle = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(oracle.createStatement()).thenReturn(st);
        when(st.executeQuery("SELECT CURRENT_SCN FROM V$DATABASE"))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist"));
        when(dataSourceManager.getConnection(any())).thenReturn(oracle);

        assertThatThrownBy(() -> service.readCurrentScn(source()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Oracle SCN");
    }
}
