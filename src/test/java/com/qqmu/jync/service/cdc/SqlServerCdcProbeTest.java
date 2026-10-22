package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P1-7(probe 侧):capture_instance 名来自 cdc.change_tables 系统表,但会被直拼进
 * {@code fn_cdc_get_all_changes_<ci>} 探测语句——与 consumer 的 changesSql 同一风险面。
 * consumer 侧已用 isSafeCaptureInstanceName 白名单拒绝;probe 必须同判:名字不合规
 * 的 CI 运行时永远不会被同步,probe 却放行会让「就绪」结论撒谎。
 */
class SqlServerCdcProbeTest {

    private final SqlServerCdcProbe probe = new SqlServerCdcProbe();
    private Connection conn;
    private Statement stmt;
    private PreparedStatement fnPs;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(conn.getMetaData()).thenReturn(md);

        stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        // P1-9:probe 先读 EngineEdition(3=Developer/本地实例)→ 正常走 Agent 检查。
        ResultSet edRs = mock(ResultSet.class);
        when(edRs.next()).thenReturn(true, false);
        when(edRs.getInt(1)).thenReturn(3);
        when(stmt.executeQuery(contains("SERVERPROPERTY"))).thenReturn(edRs);
        ResultSet cdcRs = mock(ResultSet.class);
        when(cdcRs.next()).thenReturn(true, false);
        when(cdcRs.getBoolean(1)).thenReturn(true);
        when(stmt.executeQuery(contains("is_cdc_enabled"))).thenReturn(cdcRs);
        ResultSet agentRs = mock(ResultSet.class);
        when(agentRs.next()).thenReturn(true, false);
        when(agentRs.getString(1)).thenReturn("Running");
        when(stmt.executeQuery(contains("dm_server_services"))).thenReturn(agentRs);
        ResultSet roleRs = mock(ResultSet.class);
        when(roleRs.next()).thenReturn(true, false);
        when(roleRs.getInt(1)).thenReturn(1);
        when(stmt.executeQuery(contains("IS_MEMBER"))).thenReturn(roleRs);

        fnPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("fn_cdc_get_all_changes"))).thenReturn(fnPs);
        ResultSet fnRs = mock(ResultSet.class);
        when(fnRs.next()).thenReturn(false);
        when(fnPs.executeQuery()).thenReturn(fnRs);

        // 已捕获过变更的常态:min_lsn / max_lsn 都非 NULL,可调 fn 探测。
        // 匹配用 "AS min_lsn" 而不是 fn_cdc_get_min_lsn:后者也出现在
        // fn_cdc_get_all_changes 探测语句的参数里,两个桩会互相吞掉。
        PreparedStatement boundsPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("AS min_lsn"))).thenReturn(boundsPs);
        ResultSet boundsRs = mock(ResultSet.class);
        when(boundsRs.next()).thenReturn(true, false);
        when(boundsRs.getBytes(1)).thenReturn(new byte[10]);
        when(boundsRs.getBytes(2)).thenReturn(new byte[10]);
        when(boundsPs.executeQuery()).thenReturn(boundsRs);
    }

    /** change_tables 对 [dbo, orders] 返回给定的 capture_instance 名。 */
    private void captureInstanceNamed(String ciName) throws SQLException {
        PreparedStatement changeTablesPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("cdc.change_tables"))).thenReturn(changeTablesPs);
        ResultSet ciRs = mock(ResultSet.class);
        when(ciRs.next()).thenReturn(true, false);
        when(ciRs.getString(1)).thenReturn(ciName);
        when(changeTablesPs.executeQuery()).thenReturn(ciRs);
    }

    private CdcProbeResult.Check check(CdcProbeResult result) {
        return result.getChecks().stream()
                .filter(c -> c.getName().equals(CdcProbeResult.MSSQL_CAPTURE_INSTANCE))
                .findFirst().orElseThrow();
    }

    @Test
    void safeCaptureInstanceNameProbesTheFnAndPasses() throws SQLException {
        captureInstanceNamed("dbo_orders_CT");

        CdcProbeResult result = probe.probe(conn,
                Collections.singletonList(new String[]{"dbo", "orders"}));

        assertThat(check(result).isPassed()).isTrue();
        verify(fnPs).executeQuery(); // 真实可调用探测仍然执行
    }

    @Test
    void unsafeCaptureInstanceNameFailsWithoutExecutingFnCall() throws SQLException {
        captureInstanceNamed("dbo_orders'; DROP TABLE x--");

        CdcProbeResult result = probe.probe(conn,
                Collections.singletonList(new String[]{"dbo", "orders"}));

        CdcProbeResult.Check ci = check(result);
        assertThat(ci.isPassed()).isFalse();
        assertThat(ci.getDetail()).contains("dbo_orders'; DROP TABLE x--")
                .contains("[A-Za-z0-9_]");
        // 白名单外的名字绝不允许进入任何拼接语句。
        verify(conn, never()).prepareStatement(contains("fn_cdc_get_all_changes"));
        assertThat(result.passed()).isFalse();
    }

    /**
     * 实证(SQL Server 2022 容器):capture 实例刚建好、capture 作业还没处理过任何事务时,
     * {@code sys.fn_cdc_get_max_lsn()} 为 NULL,此时任何 {@code fn_cdc_get_all_changes}
     * 调用都报 Msg 313 —— 旧实现把它当成「实例存在但不可读,去查 @role_name」,在完全
     * 正常的首次配置上给出误导性结论。实例本身是就绪的,只是可读性暂时无从探测。
     */
    @Test
    void freshCaptureInstanceWithNoCapturedChangeIsNotMisreportedAsUnreadable()
            throws SQLException {
        captureInstanceNamed("dbo_t1");
        PreparedStatement boundsPs = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("AS min_lsn"))).thenReturn(boundsPs);
        ResultSet boundsRs = mock(ResultSet.class);
        when(boundsRs.next()).thenReturn(true, false);
        when(boundsRs.getBytes(1)).thenReturn(new byte[10]);   // CI 起始 LSN 已有
        when(boundsRs.getBytes(2)).thenReturn(null);           // 但没有任何已捕获变更
        when(boundsPs.executeQuery()).thenReturn(boundsRs);

        CdcProbeResult result = probe.probe(conn,
                Collections.singletonList(new String[]{"dbo", "t1"}));

        CdcProbeResult.Check ci = check(result);
        assertThat(ci.isPassed()).isTrue();
        assertThat(ci.getDetail()).contains("Agent")
                .doesNotContain("not readable")
                .doesNotContain("@role_name");
        // Msg 313 是必然结果,不该再去踩。
        verify(conn, never()).prepareStatement(contains("fn_cdc_get_all_changes"));
    }

    @Test
    void emptySchemaTablesSkipsCaptureInstanceCheck() {
        CdcProbeResult result = probe.probe(conn, List.of());
        assertThat(result.getChecks()).noneMatch(
                c -> c.getName().equals(CdcProbeResult.MSSQL_CAPTURE_INSTANCE));
    }

    // —— P1-9:Azure SQL DB(EngineEdition=5)不支持 CDC,必须明确失败并跳过 Agent 检查 ——

    private CdcProbeResult.Check agentCheck(CdcProbeResult result) {
        return result.getChecks().stream()
                .filter(c -> c.getName().equals(CdcProbeResult.MSSQL_AGENT_RUNNING))
                .findFirst().orElseThrow();
    }

    @Test
    void azureSqlDbFailsAgentCheckAndSkipsDmServerServices() throws SQLException {
        // 旧行为:dm_server_services 在 Azure 上查不到 Agent → 报「install/start it」,
        // 但 Azure SQL DB 根本无法安装 Agent,整改指引把用户带进死胡同。
        ResultSet edRs = mock(ResultSet.class);
        when(edRs.next()).thenReturn(true, false);
        when(edRs.getInt(1)).thenReturn(5);
        when(stmt.executeQuery(contains("SERVERPROPERTY"))).thenReturn(edRs);

        CdcProbeResult result = probe.probe(conn, List.of());

        CdcProbeResult.Check agent = agentCheck(result);
        assertThat(agent.isPassed()).isFalse();
        assertThat(agent.getDetail()).contains("Azure SQL Database");
        // Agent 检查的 dm_server_services 查询根本不该发出。
        verify(stmt, never()).executeQuery(contains("dm_server_services"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void engineEditionQueryFailureFallsBackToNormalAgentCheck() throws SQLException {
        // 判不出 EngineEdition(旧驱动/权限)→ 保守走原 Agent 检查,不得误报 Azure。
        when(stmt.executeQuery(contains("SERVERPROPERTY")))
                .thenThrow(new SQLException("permission denied"));

        CdcProbeResult result = probe.probe(conn, List.of());

        verify(stmt).executeQuery(contains("dm_server_services"));
        assertThat(agentCheck(result).isPassed()).isTrue();
    }
}
