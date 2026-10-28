package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * PG logical CDC 就绪探测单测。Mock 意图同 BinlogCdcProbeTest:拼一台「一切正常」的
 * PG 源库(wal_level=logical、REPLICATION 角色、test_decoding 可用),各测试翻转一项。
 *
 * <p>核心钉住 pg_class.relreplident 的官方语义:
 * {@code d}=DEFAULT(主键,可用)/ {@code f}=FULL(全行旧镜像,可用)/
 * {@code i}=INDEX(用指定唯一索引列作 old key,<b>可用</b>)/
 * {@code n}=NOTHING(<b>不可用</b> — UPDATE/DELETE 事件不带任何旧键,
 * 消费端无法定位原行,upsert 残留旧行、delete 落空)。
 * 注释与判定曾把 i/n 写反:误拒可用的 INDEX、放行不可用的 NOTHING,
 * 且失败提示会把 DBA 引向错误方向。
 */
class PgLogicalReplicationCdcProbeTest {

    private final PgLogicalReplicationCdcProbe probe = new PgLogicalReplicationCdcProbe();
    private Connection conn;
    private Statement stmt;
    /** ⑤ 起槽后可读性的两条语句:peek 与 advance 各自的 PreparedStatement。 */
    private PreparedStatement peekPs;
    private PreparedStatement advancePs;
    /** ⑥ 查 relreplident 的 PreparedStatement:一条 SQL 带上本次要问的全部表名。 */
    private PreparedStatement identityPs;
    /** 每批 executeQuery 绑定过的表名(参数 1 是 schema)。 */
    private final List<String> identityBindings = new ArrayList<>();
    /** 目录里存在的表 → relreplident;未列出的表按 {@link #defaultReplicaIdentity} 处理。 */
    private final Map<String, String> replicaIdentityByName = new HashMap<>();
    /** 未显式列出的表被 stub 的 relreplident;null = 目录里查不到(表不存在)。 */
    private String defaultReplicaIdentity = "d";

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(conn.getMetaData()).thenReturn(md);

        // ②③ 走 Statement,按 SQL 内容分发;④ 的 slot create/drop 是 execute(),mock 默认放行。
        when(stmt.executeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("wal_level")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn("logical");
            } else if (sql.contains("rolreplication")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getBoolean(1)).thenReturn(true);
                when(rs.getBoolean(2)).thenReturn(false);
            } else if (sql.contains("pg_replication_slots")) {
                // P1-9(I6)槽余量:健康源库默认 1/10。
                when(rs.next()).thenReturn(true, false);
                when(rs.getInt(1)).thenReturn(1);
                when(rs.getInt(2)).thenReturn(10);
            } else if (sql.contains("pg_current_wal_lsn")
                    || sql.contains("pg_current_xlog_location")) {
                // ⑤ 的 SQL 面探测要一个真实位点作 advance 目标。
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn("0/16B6C50");
            }
            return rs;
        });

        // ⑥ relreplident 走 PreparedStatement(pg_class JOIN pg_namespace)。一次查询带多张
        // 表:mock 记录绑定的表名,executeQuery 只为目录里「存在」的表产行。
        identityPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("relreplident"))).thenReturn(identityPs);
        doAnswer(inv -> {
            if ((Integer) inv.getArgument(0) >= 2) {
                identityBindings.add(inv.getArgument(1));
            }
            return null;
        }).when(identityPs).setString(anyInt(), anyString());
        when(identityPs.executeQuery()).thenAnswer(inv -> {
            List<String[]> rows = new ArrayList<>();
            for (String name : identityBindings) {
                String ident = replicaIdentityByName.containsKey(name)
                        ? replicaIdentityByName.get(name) : defaultReplicaIdentity;
                if (ident != null) {
                    rows.add(new String[]{ident, name});
                }
            }
            identityBindings.clear();
            return resultSetOf(rows);
        });

        // ⑤ 的 SQL 面探测:peek 读一个空槽(无行),advance 回一行 end_lsn。
        peekPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("pg_logical_slot_peek_changes"))).thenReturn(peekPs);
        ResultSet emptyPeek = mock(ResultSet.class);
        when(emptyPeek.next()).thenReturn(false);
        when(peekPs.executeQuery()).thenReturn(emptyPeek);

        advancePs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("pg_replication_slot_advance"))).thenReturn(advancePs);
        ResultSet advanced = mock(ResultSet.class);
        when(advanced.next()).thenReturn(true, false);
        when(advanced.getString(1)).thenReturn("0/16B6C50");
        when(advancePs.executeQuery()).thenReturn(advanced);
    }

    private void replicaIdentity(String ident) {
        defaultReplicaIdentity = ident;
        replicaIdentityByName.clear();
    }

    /** A one-use ResultSet over {@code [relreplident, relname]} rows. */
    private static ResultSet resultSetOf(List<String[]> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Iterator<String[]> it = rows.iterator();
        String[][] current = new String[1][];
        when(rs.next()).thenAnswer(inv -> {
            boolean has = it.hasNext();
            current[0] = has ? it.next() : null;
            return has;
        });
        when(rs.getString(1)).thenAnswer(inv -> current[0][0]);
        when(rs.getString(2)).thenAnswer(inv -> current[0][1]);
        return rs;
    }

    private CdcProbeResult.Check check(CdcProbeResult result, String name) {
        return result.getChecks().stream()
                .filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    private CdcProbeResult probeOneTable() {
        return probe.probe(conn, List.<String[]>of(new String[]{"public", "t1"}));
    }

    @Test
    void healthySourcePassesAllChecks() throws SQLException {
        replicaIdentity("d");
        CdcProbeResult result = probeOneTable();
        assertThat(result.passed()).isTrue();
        assertThat(check(result, CdcProbeResult.PG_SOURCE).isPassed()).isTrue();
        assertThat(check(result, CdcProbeResult.PG_WAL_LEVEL).isPassed()).isTrue();
        assertThat(check(result, CdcProbeResult.PG_REPLICATION_ROLE).isPassed()).isTrue();
        assertThat(check(result, CdcProbeResult.PG_SLOT_HEADROOM).isPassed()).isTrue();
        assertThat(check(result, CdcProbeResult.PG_PLUGIN_AVAILABLE).isPassed()).isTrue();
        assertThat(check(result, CdcProbeResult.PG_REPLICA_IDENTITY).isPassed()).isTrue();
    }

    // —— v1.9.0:PG 家族（openGauss）——

    /** 重新 stub 产品名:方言判定只看服务器自报的产品名。 */
    private void productName(String product) throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn(product);
        when(conn.getMetaData()).thenReturn(md);
    }

    /** 重新 stub 角色属性查询（② 的 rolreplication / rolsuper）。 */
    private void stubRole(boolean replication, boolean superuser) throws SQLException {
        when(stmt.executeQuery(contains("rolreplication"))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getBoolean(1)).thenReturn(replication);
            when(rs.getBoolean(2)).thenReturn(superuser);
            return rs;
        });
    }

    @Test
    void openGaussSourcePassesTheProductCheck() throws SQLException {
        productName("openGauss 5.0.0");
        CdcProbeResult.Check source = check(probeOneTable(), CdcProbeResult.PG_SOURCE);
        assertThat(source.isPassed()).isTrue();
        assertThat(source.getDetail()).contains("openGauss");
    }

    @Test
    void openGaussWithoutReplicationAttributeStillPassesTheRoleCheck() throws SQLException {
        // docker 实测:openGauss 5.0.0 的 gaussdb 账号 rolsuper/rolreplication 全为 f,
        // 但 pg_create_logical_replication_slot 照样成功。建槽探测（⑤）才是权威。
        productName("openGauss 5.0.0");
        stubRole(false, false);
        CdcProbeResult.Check role = check(probeOneTable(), CdcProbeResult.PG_REPLICATION_ROLE);
        assertThat(role.isPassed()).isTrue();
        assertThat(role.getDetail()).contains("openGauss");
    }

    /** 重新 stub {@code SELECT version()} 的横幅（真库上 openGauss 唯一的识别证据）。 */
    private void versionBanner(String banner) throws SQLException {
        when(stmt.executeQuery(contains("version()"))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(banner);
            return rs;
        });
    }

    /**
     * 真库实测(org.opengauss.Driver 6.0.0-og):openGauss 的 {@code productName} 就是
     * "PostgreSQL",账号 {@code rolsuper/rolreplication} 全为 f。只看产品名会把这种源
     * 判成"缺 REPLICATION 权限"而拒之门外——必须靠 {@code SELECT version()} 横幅识别。
     */
    @Test
    void openGaussBehindAPostgreSQLProductNameStillPassesTheRoleCheck() throws SQLException {
        versionBanner("(openGauss 5.0.0 build ) compiled at 2023-04-18 10:27:05 commit 0"
                + " last mr  on aarch64-unknown-linux-gnu, compiled by g++ (GCC) 7.3.0, 64-bit");
        stubRole(false, false);
        CdcProbeResult result = probeOneTable();
        CdcProbeResult.Check role = check(result, CdcProbeResult.PG_REPLICATION_ROLE);
        assertThat(role.isPassed()).isTrue();
        assertThat(role.getDetail()).contains("openGauss");
        assertThat(check(result, CdcProbeResult.PG_SOURCE).getDetail())
                .contains("openGauss-compatible");
    }

    @Test
    void vanillaPostgresWithoutReplicationAttributeFailsTheRoleCheck() throws SQLException {
        productName("PostgreSQL 16.4");
        stubRole(false, false);
        CdcProbeResult.Check role = check(probeOneTable(), CdcProbeResult.PG_REPLICATION_ROLE);
        assertThat(role.isPassed()).isFalse();
        assertThat(role.getDetail()).contains("REPLICATION");
    }

    // —— v1.9.0:enable_slot_log 只属于 openGauss ——

    /** 覆盖 setUp 的 anyString 分发:目录里有没有 {@code pg_lsn} 类型决定方言。 */
    private void capability(long hasPgLsn) throws SQLException {
        when(stmt.executeQuery(contains("pg_type"))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong(1)).thenReturn(hasPgLsn);
            return rs;
        });
    }

    /**
     * 原生 PG 没有 {@code enable_slot_log} 这个 GUC:每次探测都发一条注定失败的
     * {@code SHOW},白白多一次往返(错误被吞掉,不影响结论)。
     */
    @Test
    void vanillaPostgresProbeDoesNotPokeTheOpenGaussOnlyGuc() throws SQLException {
        capability(1);
        assertThat(check(probeOneTable(), CdcProbeResult.PG_PLUGIN_AVAILABLE).isPassed()).isTrue();
        verify(stmt, never()).executeQuery(contains("enable_slot_log"));
    }

    /** openGauss 上这条提示是真实可行动的:槽能建但不跨重启。 */
    @Test
    void openGaussProbeKeepsTheSlotLogHint() throws SQLException {
        capability(0);
        when(stmt.executeQuery(contains("enable_slot_log"))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn("off");
            return rs;
        });
        CdcProbeResult.Check plugin = check(probeOneTable(),
                CdcProbeResult.PG_PLUGIN_AVAILABLE);
        assertThat(plugin.isPassed()).isTrue();
        assertThat(plugin.getDetail()).contains("enable_slot_log=off");
    }

    // —— v1.9.0:⑤ 在起槽后真跑一遍消费链路要用的语句 ——
    // 信创内核各家的逻辑解码 SQL 面并不一致(列名 lsn/location、函数签名、advance 是否存在),
    // 只看「槽能建」会把不兼容的内核放行到同步中途才炸。探测必须跑一遍真实语句。

    /** 方言选定的 peek 语句在真库上跑不通时,插件检查必须失败——而不是报告「可用」。 */
    @Test
    void peekStatementTheServerCannotRunFailsThePluginCheck() throws SQLException {
        when(peekPs.executeQuery()).thenThrow(new SQLException(
                "function pg_logical_slot_peek_changes(character varying, unknown, bigint,"
                        + " text[]) does not exist"));
        CdcProbeResult.Check plugin = check(probeOneTable(), CdcProbeResult.PG_PLUGIN_AVAILABLE);
        assertThat(plugin.isPassed()).isFalse();
        assertThat(plugin.getDetail()).contains("peek statement");
    }

    /** advance(PostgreSQL 11+ 才有的函数)缺失/kernel 不支持时同样在门口失败。 */
    @Test
    void advanceStatementTheServerCannotRunFailsThePluginCheck() throws SQLException {
        when(advancePs.executeQuery()).thenThrow(
                new SQLException("function pg_replication_slot_advance does not exist"));
        CdcProbeResult.Check plugin = check(probeOneTable(), CdcProbeResult.PG_PLUGIN_AVAILABLE);
        assertThat(plugin.isPassed()).isFalse();
        assertThat(plugin.getDetail()).contains("advance statement");
    }

    /** 探测用的位点必须来自方言自己的当前位置查询,并原样交给 advance。 */
    @Test
    void probeAdvancesTheProbeSlotToTheDialectsCurrentPosition() throws SQLException {
        capability(1);  // 原生 PG:pg_lsn 存在 → pg_current_wal_lsn()
        probeOneTable();
        verify(stmt).executeQuery(contains("pg_current_wal_lsn"));
        verify(peekPs).setInt(2, 1);
        verify(advancePs).setString(2, "0/16B6C50");
    }

    /** 没有 pg_lsn 类型的内核走 xlog 位置函数:方言决定语句,不是品牌决定。 */
    @Test
    void openGaussSurfaceUsesItsOwnPositionFunction() throws SQLException {
        capability(0);
        probeOneTable();
        verify(stmt).executeQuery(contains("pg_current_xlog_location"));
        verify(advancePs).setString(2, "0/16B6C50");
    }

    // —— P1-9(I6):max_replication_slots 余量 ——

    /** 覆盖 setUp 的 anyString 分发:槽用量/上限由本方法钉死。 */
    private void stubHeadroom(int used, int max) throws SQLException {
        when(stmt.executeQuery(contains("pg_replication_slots"))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getInt(1)).thenReturn(used);
            when(rs.getInt(2)).thenReturn(max);
            return rs;
        });
    }

    @Test
    void slotHeadroomExhaustedFailsWithRemediation() throws SQLException {
        // 多项目部署打满槽配额后,pg_create_logical_replication_slot 直接报错、
        // consumer 进 TRANSIENT 重试循环——probe 必须提前失败并给出整改文本。
        stubHeadroom(10, 10);
        CdcProbeResult result = probeOneTable();
        CdcProbeResult.Check hr = check(result, CdcProbeResult.PG_SLOT_HEADROOM);
        assertThat(hr.isPassed()).isFalse();
        assertThat(hr.getDetail()).contains("max_replication_slots");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void slotHeadroomAvailablePasses() throws SQLException {
        stubHeadroom(3, 10);
        assertThat(check(probeOneTable(), CdcProbeResult.PG_SLOT_HEADROOM).isPassed()).isTrue();
    }

    // —— P1-9(I5):一次性 probe slot 泄漏必须 WARN 点名 ——

    @Test
    void leakedProbeSlotIsWarnedByName() throws SQLException {
        // create 成功、两次 drop 都失败 → 槽确定泄漏:占用 max_replication_slots
        // 配额并钉住 WAL,直到 DBA 手工清理。WARN 必须报出槽名。
        when(stmt.execute(contains("pg_create_logical_replication_slot"))).thenReturn(false);
        when(stmt.execute(contains("pg_drop_replication_slot")))
                .thenThrow(new SQLException("must be owner of replication slot"));

        ListAppender<ILoggingEvent> appender = LogCapture.attach(PgLogicalReplicationCdcProbe.class);
        try {
            CdcProbeResult result = probeOneTable();
            assertThat(check(result, CdcProbeResult.PG_PLUGIN_AVAILABLE).isPassed()).isFalse();
            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("jync_probe_slot_"));
        } finally {
            LogCapture.detach(PgLogicalReplicationCdcProbe.class, appender);
        }
    }

    @Test
    void createFailureDoesNotWarnAboutLeak() throws SQLException {
        // create 本身失败(如槽配额已满)→ 槽从未存在,不得发假泄漏 WARN。
        when(stmt.execute(contains("pg_create_logical_replication_slot")))
                .thenThrow(new SQLException("all replication slots are in use"));

        ListAppender<ILoggingEvent> appender = LogCapture.attach(PgLogicalReplicationCdcProbe.class);
        try {
            CdcProbeResult result = probeOneTable();
            assertThat(check(result, CdcProbeResult.PG_PLUGIN_AVAILABLE).isPassed()).isFalse();
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            LogCapture.detach(PgLogicalReplicationCdcProbe.class, appender);
        }
    }

    @Test
    void nothingIdentityMustFail() throws SQLException {
        // n=NOTHING:无旧键 → UPDATE/DELETE 无法定位原行,必须拒绝。
        replicaIdentity("n");
        CdcProbeResult result = probeOneTable();
        assertThat(check(result, CdcProbeResult.PG_REPLICA_IDENTITY).isPassed()).isFalse();
        assertThat(check(result, CdcProbeResult.PG_REPLICA_IDENTITY).getDetail())
                .contains("public.t1");
    }

    @Test
    void indexIdentityMustPass() throws SQLException {
        // i=INDEX:old key 用该唯一索引列,足以定位原行,必须放行。
        replicaIdentity("i");
        assertThat(check(probeOneTable(), CdcProbeResult.PG_REPLICA_IDENTITY).isPassed())
                .isTrue();
    }

    @Test
    void defaultAndFullIdentityPass() throws SQLException {
        replicaIdentity("d");
        assertThat(check(probeOneTable(), CdcProbeResult.PG_REPLICA_IDENTITY).isPassed())
                .isTrue();
        replicaIdentity("f");
        assertThat(check(probeOneTable(), CdcProbeResult.PG_REPLICA_IDENTITY).isPassed())
                .isTrue();
    }

    /** 加了引号建的表在目录里保留真实大小写,查询必须原样问,不能无条件折叠。 */
    @Test
    void mixedCaseTableIsLookedUpVerbatim() throws SQLException {
        replicaIdentity("d");
        CdcProbeResult.Check identity = check(
                probe.probe(conn, List.<String[]>of(new String[]{"Public", "MyTable"})),
                CdcProbeResult.PG_REPLICA_IDENTITY);
        assertThat(identity.isPassed()).isTrue();
        // 混合大小写的 schema 还会按折叠形式再问一次,所以用 atLeastOnce。
        verify(identityPs, org.mockito.Mockito.atLeastOnce()).setString(1, "Public");
        verify(identityPs, org.mockito.Mockito.atLeastOnce()).setString(2, "MyTable");
    }

    /** 原名查不到时按 SQL 未加引号的语义折叠再查一次,而不是直接判表不存在。 */
    @Test
    void missingExactNameIsRetriedFoldedBeforeReportingTheTableMissing() throws SQLException {
        replicaIdentity(null);                       // 原名在目录里不存在
        replicaIdentityByName.put("mytable", "d");   // 只有折叠后的名字存在

        CdcProbeResult.Check identity = check(
                probe.probe(conn, List.<String[]>of(new String[]{"Public", "MyTable"})),
                CdcProbeResult.PG_REPLICA_IDENTITY);
        assertThat(identity.isPassed()).isTrue();
        verify(identityPs, org.mockito.Mockito.atLeastOnce()).setString(3, "mytable");
    }

    @Test
    void missingTableRowFailsIdentityCheck() throws SQLException {
        // pg_class 查不到行(表不存在/schema 错)→ ident=null → 必须失败。
        replicaIdentity(null);
        CdcProbeResult.Check identity = check(probeOneTable(),
                CdcProbeResult.PG_REPLICA_IDENTITY);
        assertThat(identity.isPassed()).isFalse();
        // Missing and NOTHING are different problems with different fixes: one is a stale
        // selection, the other an ALTER TABLE. The detail must not send the DBA to the
        // wrong one.
        assertThat(identity.getDetail()).contains("not found");
    }

    /**
     * A project may select hundreds of tables (bulk creation allows up to thousands). One
     * catalog round trip per table made the probe — which also runs while the project
     * detail page renders — scale with the selection. All tables of one schema share one
     * query.
     */
    @Test
    void tablesOfOneSchemaShareACatalogQuery() throws SQLException {
        replicaIdentity("d");
        probe.probe(conn, List.of(
                new String[]{"public", "a"},
                new String[]{"public", "b"},
                new String[]{"public", "c"}));

        verify(identityPs, times(1)).executeQuery();
    }

    /** Different schemas cannot share one predicate — one query each, still not one per table. */
    @Test
    void tablesOfDifferentSchemasAreGroupedBySchema() throws SQLException {
        replicaIdentity("d");
        probe.probe(conn, List.of(
                new String[]{"app", "a"},
                new String[]{"app", "b"},
                new String[]{"sales", "c"}));

        verify(identityPs, times(2)).executeQuery();
    }

    @Test
    void walLevelReplicaFailsWithRemediation() throws SQLException {
        replicaIdentity("d");
        when(stmt.executeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("wal_level")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn("replica");
            } else if (sql.contains("rolreplication")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getBoolean(1)).thenReturn(true);
            }
            return rs;
        });
        CdcProbeResult result = probeOneTable();
        assertThat(check(result, CdcProbeResult.PG_WAL_LEVEL).isPassed()).isFalse();
        assertThat(check(result, CdcProbeResult.PG_WAL_LEVEL).getDetail())
                .contains("wal_level='logical'");
    }

    @Test
    void emptyOwnerTablesSkipsTableLevelCheck() {
        CdcProbeResult result = probe.probe(conn, List.of());
        assertThat(result.getChecks())
                .noneMatch(c -> c.getName().equals(CdcProbeResult.PG_REPLICA_IDENTITY));
    }
}
