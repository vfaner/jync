package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * PG 逻辑复制 slot 命名与回收的项目删除路径(P0-5)。
 *
 * <p>pg_logical_slot_get_changes 的 slot 是持久对象:项目删除后 slot 仍留在源库,
 * PG 会为它无限保留 WAL —— 源库磁盘被一个已删除的项目撑爆。dropSlotQuietly 是
 * ProjectService.delete 的 best-effort 回收口:必须吞掉 SQLException(源库不可达
 * 不能挡住项目删除),且 SQL 里的 slot 名必须与消费端 slotName 完全一致。
 */
class PgLogicalCdcConsumerTest {

    private final SyncProperties properties = new SyncProperties();

    @Test
    void slotNameIsPrefixPlusProjectId() {
        // 基线防回归:dropSlotQuietly 与 ensureSlot 必须用同一个名字,否则删的是
        // 不存在的 slot、真 slot 继续膨胀。
        assertThat(PgLogicalCdcConsumer.slotName(42L, properties)).isEqualTo("jync_42");
        SyncProperties custom = new SyncProperties();
        custom.getCdc().getPg().setSlotPrefix("cdc_");
        assertThat(PgLogicalCdcConsumer.slotName(42L, custom)).isEqualTo("cdc_42");
    }

    @Test
    void dropSlotQuietlyExecutesDropForTheProjectsSlot() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);

        PgLogicalCdcConsumer.dropSlotQuietly(conn, 42L, properties);

        verify(st).execute("SELECT pg_drop_replication_slot('jync_42')");
    }

    @Test
    void dropSlotQuietlySwallowsSourceFailures() throws SQLException {
        // 源库不可达/slot 已被人工删除:项目删除不能被回收失败挡住(best-effort)。
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenThrow(new SQLException("connection refused"));

        assertThatCode(() -> PgLogicalCdcConsumer.dropSlotQuietly(conn, 42L, properties))
                .doesNotThrowAnyException();
    }

    // —— P2-9:slot 创建竞态(TOCTOU)42710 吞掉降 DEBUG ——

    @Test
    void ensureSlotToleratesConcurrentCreation42710() throws SQLException {
        // ensureSlot 先查后建:两个节点/两次启动贴脸时,SELECT 都说"不存在",
        // 一方 CREATE 成功,另一方吃 42710 duplicate_object。旧行为:SQLException
        // 上抛 → drain 中止、退避重试一整个周期(自愈但白扔+错误噪音)。
        // 修复:42710 吞掉记 DEBUG —— slot 存在正是想要的状态。
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false); // SELECT 说 slot 不存在
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(st.execute(contains("pg_create_logical_replication_slot")))
                .thenThrow(new SQLException(
                        "replication slot \"jync_1\" already exists", "42710"));

        assertThatCode(() -> PgLogicalCdcConsumer.ensureSlot(conn, "jync_1"))
                .doesNotThrowAnyException();
    }

    @Test
    void ensureSlotStillPropagatesRealErrors() throws SQLException {
        // 守护:非 42710 的建槽失败(如 42501 权限不足)必须照常上抛——吞错的代价
        // 是消费端对着永远建不起来的 slot 空转。
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(st.execute(contains("pg_create_logical_replication_slot")))
                .thenThrow(new SQLException("permission denied to create replication slot",
                        "42501"));

        assertThatThrownBy(() -> PgLogicalCdcConsumer.ensureSlot(conn, "jync_1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    // —— P2-10:slot 名白名单(纵深;PG 服务端本就只接受 [a-z0-9_]) ——

    @Test
    void slotNameRejectsPrefixOutsidePgWhitelist() {
        // PG 对 slot 名只接受小写字母/数字/下划线。旧行为:转义后拼 SQL,PG 在
        // create 时报它自己的错(甚至 drop 一条永远不会存在的 slot)。纵深:在
        // slotName() 就 fail-fast,报出是 prefix 配置问题。
        SyncProperties evil = new SyncProperties();
        evil.getCdc().getPg().setSlotPrefix("bad'name");
        assertThatThrownBy(() -> PgLogicalCdcConsumer.slotName(1L, evil))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slot-prefix");
        SyncProperties upper = new SyncProperties();
        upper.getCdc().getPg().setSlotPrefix("JYNC_");
        assertThatThrownBy(() -> PgLogicalCdcConsumer.slotName(1L, upper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dropSlotQuietlyWithRejectedPrefixStillNeverThrows() throws SQLException {
        // P2-10 裁定:含引号的前缀不再"转义后拼 SQL"(PG 服务端反正会拒),改为在
        // slotName() fail-fast。删除路径因此变成:IllegalStateException 被
        // dropSlotQuietly 的 RuntimeException catch 吞掉记 WARN,一条 SQL 都不发——
        // 项目删除永远不能被一个奇怪的 slot-prefix 配置挡住。
        SyncProperties evil = new SyncProperties();
        evil.getCdc().getPg().setSlotPrefix("jy'nc_");
        Connection conn = mock(Connection.class);

        assertThatCode(() -> PgLogicalCdcConsumer.dropSlotQuietly(conn, 42L, evil))
                .doesNotThrowAnyException();
        verify(conn, org.mockito.Mockito.never()).createStatement();
    }

    @Test
    void slotNameAcceptsLowercaseAlnumUnderscorePrefix() {
        // 守护:默认与常规小写前缀不受影响。
        assertThat(PgLogicalCdcConsumer.slotName(1L, properties)).isEqualTo("jync_1");
        SyncProperties custom = new SyncProperties();
        custom.getCdc().getPg().setSlotPrefix("cdc9_");
        assertThat(PgLogicalCdcConsumer.slotName(1L, custom)).isEqualTo("cdc9_1");
    }

    @Test
    void isSafeSlotNamePredicate() {
        assertThat(PgLogicalCdcConsumer.isSafeSlotName("jync_42")).isTrue();
        assertThat(PgLogicalCdcConsumer.isSafeSlotName("jync_probe_slot_123")).isTrue();
        assertThat(PgLogicalCdcConsumer.isSafeSlotName("bad'name")).isFalse();
        assertThat(PgLogicalCdcConsumer.isSafeSlotName("UPPER")).isFalse();
        assertThat(PgLogicalCdcConsumer.isSafeSlotName("with space")).isFalse();
        assertThat(PgLogicalCdcConsumer.isSafeSlotName("")).isFalse();
        assertThat(PgLogicalCdcConsumer.isSafeSlotName(null)).isFalse();
    }

    // —— F-8(docker 实测):slot 必须在基线之前建好 ——

    @Test
    void slotIsCreatedBeforeTheBaselineRuns() throws Exception {
        // 实测根因:旧代码先跑基线、首轮 drain 才懒建 slot。基线完成后、slot 建立前
        // 提交的事务落在 slot 起始位点之前 —— pg_create_logical_replication_slot 只
        // 保留创建点之后的 WAL,那批变更永久丢失(UPDATE 永不生效即此症)。
        // 钉住顺序:启动 → 开连接建 slot → 才允许 runBaseline。
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(false); // 进入 drain 循环即让出,测试只关心启动顺序

        com.qqmu.jync.service.sync.SyncContext ctx =
                mock(com.qqmu.jync.service.sync.SyncContext.class);
        DatabaseConfig sourceConfig = mock(DatabaseConfig.class);
        when(ctx.getSourceConfig()).thenReturn(sourceConfig);

        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = mock(CdcPosition.class);
        when(anchored.getLsn()).thenReturn("0/16BC000");
        when(positionStore.find(1L))
                .thenReturn(Optional.empty())                  // 启动时:基线未做
                .thenReturn(Optional.of(anchored));           // 基线后:LSN 锚点就绪

        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);

        // 预建 slot 用的源连接:SELECT 说不存在 → CREATE 成功。
        Connection slotConn = mock(Connection.class);
        Statement slotStmt = mock(Statement.class);
        when(slotConn.createStatement()).thenReturn(slotStmt);
        ResultSet slotRs = mock(ResultSet.class);
        when(slotRs.next()).thenReturn(false);
        when(slotStmt.executeQuery(anyString())).thenReturn(slotRs);

        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(sourceConfig)).thenReturn(slotConn);

        PgLogicalCdcConsumer consumer = new PgLogicalCdcConsumer(
                project, lock, ctx, positionStore, baselineService, taskStore,
                stateWriter, dsm, new CdcRuntimeStats(), properties);

        consumer.run();

        InOrder order = inOrder(dsm, baselineService, slotStmt);
        order.verify(dsm).getConnection(sourceConfig);
        order.verify(slotStmt).execute(contains("pg_create_logical_replication_slot"));
        order.verify(baselineService).runBaseline(any(), any());
        // 预建连接必须关闭:不泄漏,也避免占着源端连接池。
        verify(slotConn).close();
    }
}
