package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
    /** 当前表被 stub 的 relreplident 值;每次 executeQuery 都产一份新 ResultSet,支持重复 probe()。 */
    private String currentReplicaIdentity = "d";

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
            }
            return rs;
        });

        // ⑤ relreplident 走 PreparedStatement(pg_class JOIN pg_namespace)。
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(currentReplicaIdentity);
            return rs;
        });
    }

    private void replicaIdentity(String ident) {
        currentReplicaIdentity = ident;
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

    @Test
    void missingTableRowFailsIdentityCheck() throws SQLException {
        // pg_class 查不到行(表不存在/schema 错)→ ident=null → 必须失败。
        replicaIdentity(null);
        assertThat(check(probeOneTable(), CdcProbeResult.PG_REPLICA_IDENTITY).isPassed())
                .isFalse();
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
