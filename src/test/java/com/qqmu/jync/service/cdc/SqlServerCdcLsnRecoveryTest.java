package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * #2 + #3:SQLServer CDC 的 LSN 窗口恢复与水位推进(drain 级单测,mock 源库,
 * 不依赖 docker —— 判定逻辑可测,正如 F-13)。
 *
 * <p>#2 — 22054(invalid LSN)从「静默跳过」升级为「检测并分流」:
 * <ul>
 *   <li>① CI 中途新建/重建(start_lsn &gt; 水位)→ clamp @from_lsn 到
 *       {@code fn_cdc_get_min_lsn}(inclusive,不 increment 以免跳过边界行)重扫,
 *       自愈、零丢失(start_lsn 之前本就没有该表的捕获数据);</li>
 *   <li>② established CI 被 cleanup job purge 过水位(start_lsn ≤ 水位 &lt; min_lsn)
 *       → [水位, min_lsn) 的变更已物理删除、不可恢复 → 抛 PURGED 类 SQLException →
 *       park ERROR + 提示重基线,<b>绝不静默 clamp 丢数据</b>(用户裁定)。</li>
 * </ul>
 *
 * <p>#3 — 干净扫完即推进水位:CI 循环无异常跑完(所有 tracked CI 都扫过)后,即使
 * 0 行也把 nextLsn 推到 maxLsn。根治「tracked 表静默 + untracked 表有变更 → fromLsn
 * 卡死 → 每周期全量 discovery + 扫描窗口单调增长 → 最终喂给 #2 的 22054」。
 */
class SqlServerCdcLsnRecoveryTest {

    private static final String FROM_LSN = "0x0000000000000000000a";
    private static final String MAX_LSN  = "0x0000000000000000ffff";

    private Connection conn;
    private PreparedStatement maxPs;
    private PreparedStatement discoveryPs;
    private PreparedStatement minLsnPs;
    private PreparedStatement normalChangesPs;
    private PreparedStatement clampedChangesPs;
    /** Per-test: the discovered CI's start_lsn (canonical) and fn_cdc_get_min_lsn. */
    private final AtomicReference<String> ciStartLsn = new AtomicReference<>();
    private final AtomicReference<String> ciMinLsn = new AtomicReference<>();
    /** Per-test override for fn_cdc_get_max_lsn (a new CI can sit ABOVE it). */
    private final AtomicReference<String> maxLsn = new AtomicReference<>(MAX_LSN);
    private CdcRuntimeStats stats;
    private SqlServerCdcConsumer consumer;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        maxPs = mock(PreparedStatement.class);
        discoveryPs = mock(PreparedStatement.class);
        minLsnPs = mock(PreparedStatement.class);
        normalChangesPs = mock(PreparedStatement.class);
        clampedChangesPs = mock(PreparedStatement.class);

        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenAnswer(inv -> maxLsn.get());
        when(maxPs.executeQuery()).thenReturn(maxRs);

        // discovery: one tracked CI dbo.orders / dbo_orders; start_lsn is column 4.
        ResultSet discoveryRs = mock(ResultSet.class);
        when(discoveryRs.next()).thenReturn(true, false);
        when(discoveryRs.getString(1)).thenReturn("dbo");
        when(discoveryRs.getString(2)).thenReturn("orders");
        when(discoveryRs.getString(3)).thenReturn("dbo_orders");
        when(discoveryRs.getString(4)).thenAnswer(inv -> ciStartLsn.get());
        when(discoveryPs.executeQuery()).thenReturn(discoveryRs);

        ResultSet minLsnRs = mock(ResultSet.class);
        when(minLsnRs.next()).thenReturn(true);
        when(minLsnRs.getString(1)).thenAnswer(inv -> ciMinLsn.get());
        when(minLsnPs.executeQuery()).thenReturn(minLsnRs);

        // Clamp (inclusive) rescan is empty by default — keeps these tests off the
        // binding/metadata harness (0 rows folded → supplier.binding never called).
        // Built BEFORE the stub call: Mockito forbids nested stubbing inside thenReturn.
        ResultSet clampedRs = emptyChangesRs();
        when(clampedChangesPs.executeQuery()).thenReturn(clampedRs);

        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("fn_cdc_get_max_lsn")) return maxPs;
            if (sql.contains("cdc.change_tables")) return discoveryPs;
            if (sql.contains("fn_cdc_get_min_lsn")) return minLsnPs;
            if (sql.contains("fn_cdc_get_all_changes_")) {
                // normal path wraps @from_lsn in fn_cdc_increment_lsn; the #2 clamp
                // path binds min_lsn raw (inclusive) — distinguishable by that token.
                return sql.contains("fn_cdc_increment_lsn") ? normalChangesPs : clampedChangesPs;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        SyncContext ctx = mock(SyncContext.class);
        SyncConfig config = mock(SyncConfig.class);
        when(config.includesTable("orders")).thenReturn(true);
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getSourceSchema()).thenReturn(null);

        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-lsn");
        stats = new CdcRuntimeStats();
        consumer = new SqlServerCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), ctx,
                mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class),
                mock(DataSourceManager.class), stats, new SyncProperties());
    }

    private static ResultSet emptyChangesRs() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(0);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    @Test
    void aLegacyPersistedLsnIsNormalisedBeforeItIsBoundToTheCaptureCall() throws Exception {
        // v1.7.0 把 __$start_lsn 直接从原始二进制列取出来持久化(hex(getBytes())),
        // 存成「大写、无 0x 前缀」。此后所有版本读 LSN 都走
        // CONVERT(varchar(32), lsn, 1)(规范形:0x + 小写十六进制),回绑时又用
        // CONVERT(binary(10), ?, 1) —— style 1 只接受规范形。遗留水位因此每周期都
        // 报 Msg 8114("Error converting data type varchar to varbinary"),分类器判
        // TRANSIENT,重试到 APPLY_STRIKE_OUT 把项目永久 park。
        // 契约:进入 drain 的水位先归一化,再参与比较、绑定。
        ciStartLsn.set("0x00000000000000000001");
        maxLsn.set("0x0000002857ae02580100");
        ResultSet normalRs = emptyChangesRs(); // built first: Mockito forbids nested stubbing
        when(normalChangesPs.executeQuery()).thenReturn(normalRs);

        consumer.drain(conn, "0000002857AE025800A0");

        verify(normalChangesPs).setString(1, "0x0000002857ae025800a0");
    }

    @Test
    void aLegacyLsnAtTheWatermarkIsRecognisedAsIdle() throws Exception {
        // 同一遗留形态,但源库没有新变更:未归一化时 legacy 串与规范形 max_lsn
        // 形状不同,equalsIgnoreCase 永远不匹配 —— 本该空闲的周期被当成有待扫窗口,
        // 接着就是上面那条 Msg 8114。归一化后两者相等,必须走空闲早退。
        maxLsn.set("0x0000002857ae025800a0");
        ResultSet normalRs = emptyChangesRs();
        when(normalChangesPs.executeQuery()).thenReturn(normalRs);

        SqlServerCdcConsumer.DrainResult r = consumer.drain(conn, "0000002857AE025800A0");

        assertThat(r.nextLsn).isNull();
        verify(normalChangesPs, never()).executeQuery();
    }

    @Test
    void emptyWindowOnATrackedInstanceStillAdvancesTheWatermark() throws Exception {
        // #3: tracked CI, window (fromLsn, maxLsn] holds ZERO rows, no 22054. The window
        // is fully consumed → the watermark must advance to maxLsn even though nothing
        // folded (old code left nextLsn == fromLsn, stalling it forever).
        ciStartLsn.set("0x00000000000000000001");
        ResultSet normalRs = emptyChangesRs();
        when(normalChangesPs.executeQuery()).thenReturn(normalRs);

        SqlServerCdcConsumer.DrainResult r = consumer.drain(conn, FROM_LSN);

        assertThat(r.rows).isZero();
        assertThat(r.nextLsn).isEqualTo(MAX_LSN);
    }

    @Test
    void purgedEstablishedInstanceParksInsteadOfSilentlyClamping() throws SQLException {
        // #2②: established CI (start_lsn ≤ watermark) whose min_lsn the cleanup job
        // advanced PAST the watermark → [watermark, min_lsn) is gone. Must throw a
        // PURGED-classified SQLException (→ park ERROR), never clamp-and-lose.
        ciStartLsn.set("0x00000000000000000001"); // ≤ FROM_LSN (…0a)
        ciMinLsn.set("0x00000000000000000050");   // > FROM_LSN → purged past it
        when(normalChangesPs.executeQuery())
                .thenThrow(new SQLException("An invalid LSN was specified", null, 22054));

        assertThatThrownBy(() -> consumer.drain(conn, FROM_LSN))
                .isInstanceOf(SQLException.class)
                .satisfies(t -> assertThat(CdcFailureClassifier.classify(t))
                        .isEqualTo(CdcFailureClassifier.Kind.PURGED));
    }

    @Test
    void newlyEnabledInstanceIsClampedToMinLsnAndRecovers() throws Exception {
        // #2①: CI enabled/recreated mid-flight (start_lsn > watermark). 22054 on the
        // normal (increment) query → clamp @from_lsn to min_lsn and rescan inclusively.
        // No throw, watermark advances, and the clamp path is provably taken (min_lsn
        // queried + the non-increment changes SQL executed).
        ciStartLsn.set("0x000000000000000000ff"); // > FROM_LSN (…0a)
        ciMinLsn.set("0x000000000000000000ff");   // == start_lsn (new CI, nothing purged)
        when(normalChangesPs.executeQuery())
                .thenThrow(new SQLException("An invalid LSN was specified", null, 22054));

        SqlServerCdcConsumer.DrainResult r = consumer.drain(conn, FROM_LSN);

        assertThat(r.nextLsn).isEqualTo(MAX_LSN);
        verify(minLsnPs).executeQuery();
        verify(clampedChangesPs).executeQuery();
    }

    @Test
    void aClampOverAnUncapturedIntervalIsCountedAndDoesNotClaimNothingWasLost()
            throws Exception {
        // CI 的 start_lsn 高于水位有两种可能:① 首次启用(之前本就没捕获数据);
        // ② 基线之后重建(旧 CI 覆盖过该表,而新 CI 开始之前的变更两份 CI 都没抓到)。
        // 没有 CI 历史时二者不可区分,而 clamp 自愈是上一轮刻意加的修复,不能改成
        // 无条件 park。但旧告警断言 "no changes were lost" —— 它只证明「该 CI 之前
        // 无数据」,没证明「该表之前无变更」。误导性的安心话比不说话更糟:运维会据此
        // 跳过核对。契约:如实说明该区间从未被捕获、事件可计数(面板可见),让运维
        // 决定是否重基线。
        ciStartLsn.set("0x000000000000000000ff");
        ciMinLsn.set("0x000000000000000000ff");
        when(normalChangesPs.executeQuery())
                .thenThrow(new SQLException("An invalid LSN was specified", null, 22054));
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SqlServerCdcConsumer.class);
        try {
            consumer.drain(conn, FROM_LSN);
        } finally {
            LogCapture.detach(SqlServerCdcConsumer.class, logs);
        }

        assertThat(stats.getUncapturedGaps()).isEqualTo(1);
        List<String> warnings = LogCapture.messagesAt(Level.WARN, logs);
        assertThat(warnings).anyMatch(m -> m.contains("was never captured"));
        assertThat(warnings).noneMatch(m -> m.contains("no changes were lost"));
    }

    @Test
    void anInstanceStartingPastTheMaxLsnIsSkippedInsteadOfClampingIntoAnInvalidRange()
            throws Exception {
        // #2 追加:CI 刚启用、捕获作业尚未处理其 enable 事务时,fn_cdc_get_max_lsn()
        // 还在它的 start_lsn 之下(min_lsn 为 NULL,变更表为空)。此时 clamp 点是
        // start_lsn > maxLsn,@from_lsn > @to_lsn 是非法区间,SQL Server 报 313
        // ("The provided range of change data is not valid")。旧代码让 313 穿出 drain:
        // 每周期必败,计数满额后项目被 APPLY_STRIKE_OUT 永久 park,且提示与真实原因
        // 无关。这个 CI 在水位之后本就没有任何捕获数据可读 —— 跳过它,水位照常推进到
        // maxLsn,等捕获作业追上后下个周期自然开始读。
        ciStartLsn.set("0x00000000000000000100");
        maxLsn.set("0x000000000000000000ff");
        when(normalChangesPs.executeQuery())
                .thenThrow(new SQLException("An invalid LSN was specified", null, 22054));
        when(clampedChangesPs.executeQuery())
                .thenThrow(new SQLException(
                        "The provided range of change data is not valid.", null, 313));

        SqlServerCdcConsumer.DrainResult r = consumer.drain(conn, FROM_LSN);

        assertThat(r.rows).isZero();
        assertThat(r.nextLsn).isEqualTo("0x000000000000000000ff");
    }
}
