package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.connection.ConnectionTestService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.TargetDdlProbe;

/**
 * 连接测试对四种支持日志 CDC 的源库类型附加逐项就绪探测;目标库连接(TARGET 用途)
 * 与其他类型 cdcChecks 为 null;探测自身抛错时连接测试仍然成功(探测只是附加信息,
 * B2:不阻断)。
 */
class ConnectionTestCdcChecksTest {

    private Connection healthyMySqlConnection() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.isValid(5)).thenReturn(true);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(md.getDatabaseProductVersion()).thenReturn("8.0.36");
        when(md.getDriverName()).thenReturn("MySQL Connector/J");
        when(md.getDriverVersion()).thenReturn("8.0.33");
        when(md.getURL()).thenReturn("jdbc:mysql://localhost:3306/app");
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn("app");
        when(conn.getSchema()).thenReturn(null);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        ResultSet varRs = mock(ResultSet.class);
        // 同一个 ResultSet mock 被五次 SHOW VARIABLES 复用,每次只消费一个 next()
        when(varRs.next()).thenReturn(true);
        when(varRs.getString(2)).thenReturn("ON", "ROW", "FULL", "1", "2592000");
        when(stmt.executeQuery(any(String.class))).thenReturn(varRs);
        ResultSet grantRs = mock(ResultSet.class);
        when(grantRs.next()).thenReturn(true, false);
        when(grantRs.getString(1)).thenReturn("GRANT ALL PRIVILEGES ON *.* TO 'sync'@'%'");
        when(stmt.executeQuery("SHOW GRANTS")).thenReturn(grantRs);
        // P1-5 的 BINLOG_STATUS 检查:具体 stub 覆盖上面的 any(String) 兜底
        ResultSet statusRs = mock(ResultSet.class);
        when(statusRs.next()).thenReturn(true, false);
        when(statusRs.getString("File")).thenReturn("mysql-bin.000003");
        when(statusRs.getLong("Position")).thenReturn(154L);
        when(stmt.executeQuery("SHOW BINARY LOG STATUS")).thenReturn(statusRs);
        return conn;
    }

    /** Oracle target answering SESSION_PRIVS with {@code createTablePrivCount} rows. */
    private Connection oracleTargetConnection(long createTablePrivCount) throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.isValid(5)).thenReturn(true);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Oracle");
        when(md.getDatabaseProductVersion()).thenReturn("23.0.0.0.0");
        when(md.getDriverName()).thenReturn("Oracle JDBC driver");
        when(md.getDriverVersion()).thenReturn("23.4.0.24.05");
        when(md.getURL()).thenReturn("jdbc:oracle:thin:@localhost:1521/FREEPDB1");
        when(conn.getMetaData()).thenReturn(md);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(any(String.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            if (sql.contains("USER FROM DUAL")) {
                when(rs.getString(1)).thenReturn("C##JYNC_MINER");
            } else {
                when(rs.getLong(1)).thenReturn(createTablePrivCount);
            }
            return rs;
        });
        return conn;
    }

    private DatabaseConfig savedOracleTarget() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(2L);
        config.setName("tgt");
        config.setType(DatabaseType.ORACLE);
        config.setRole(ConnectionRole.TARGET);
        config.setHost("localhost");
        config.setPort(1521);
        config.setDatabaseName("FREEPDB1");
        config.setUsername("c##jync_miner");
        return config;
    }

    private ConnectionTestService serviceReturning(Connection conn) {
        DataSourceManager dsm = mock(DataSourceManager.class);
        try {
            when(dsm.getConnection(any(DatabaseConfig.class))).thenReturn(conn);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new ConnectionTestService(dsm, new BinlogCdcProbe(), new LogMinerCdcProbe(),
                new PgLogicalReplicationCdcProbe(), new SqlServerCdcProbe(),
                new TargetDdlProbe(), new SyncProperties());
    }

    private DatabaseConfig savedMysql() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setName("src");
        config.setType(DatabaseType.MYSQL);
        config.setHost("localhost");
        config.setPort(3306);
        config.setDatabaseName("app");
        config.setUsername("sync");
        return config;
    }

    /** v1.9.0:PG 家族源（openGauss）必须跑 PG 逻辑复制探测，而不是返回"无检查项"。 */
    @Test
    void openGaussSourceCarriesThePgProbeChecks() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.isValid(5)).thenReturn(true);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("openGauss 5.0.0");
        when(md.getDatabaseProductVersion()).thenReturn("5.0.0");
        when(md.getDriverName()).thenReturn("openGauss JDBC Driver");
        when(md.getDriverVersion()).thenReturn("5.0.0");
        when(conn.getMetaData()).thenReturn(md);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(any(String.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            if (sql.contains("wal_level")) {
                when(rs.getString(1)).thenReturn("logical");
            } else if (sql.contains("rolreplication")) {
                when(rs.getBoolean(1)).thenReturn(false);
                when(rs.getBoolean(2)).thenReturn(false);
            } else if (sql.contains("pg_replication_slots")) {
                when(rs.getInt(1)).thenReturn(1);
                when(rs.getInt(2)).thenReturn(10);
            }
            return rs;
        });

        DatabaseConfig config = new DatabaseConfig();
        config.setId(3L);
        config.setName("og-src");
        config.setType(DatabaseType.OPENGAUSS);
        config.setHost("localhost");
        config.setPort(5432);
        config.setDatabaseName("app");
        config.setUsername("sync");

        ConnectionTestService.TestResult result = serviceReturning(conn).test(config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCdcChecks()).isNotNull();
        assertThat(result.getCdcChecks())
                .anyMatch(c -> CdcProbeResult.PG_SOURCE.equals(c.getName())
                        && c.isPassed());
    }

    @Test
    void mysqlTestCarriesCdcChecks() throws SQLException {
        ConnectionTestService service = serviceReturning(healthyMySqlConnection());
        ConnectionTestService.TestResult result = service.test(savedMysql());
        assertThat(result.isSuccess()).isTrue();
        List<CdcProbeResult.Check> checks = result.getCdcChecks();
        assertThat(checks).isNotNull().hasSize(8);
        assertThat(checks).allMatch(CdcProbeResult.Check::isPassed);
    }

    @Test
    void mysqlTargetCarriesNoCdcChecks() throws SQLException {
        // CDC 只在源端挖掘日志;目标库连接测试跑源端就绪检查纯属误导(用户截图:
        // 选「目标数据库连接」+ Oracle,结果列出 LogMiner 权限等源端检查项)。
        ConnectionTestService service = serviceReturning(healthyMySqlConnection());
        DatabaseConfig target = savedMysql();
        target.setRole(ConnectionRole.TARGET);
        ConnectionTestService.TestResult result = service.test(target);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCdcChecks()).isNull();
    }

    @Test
    void oracleTargetCarriesTheCreateTablePrivilegePrecheck() throws SQLException {
        // 实测事故:目标账号没有 CREATE TABLE,配置页一路绿灯,直到建表才甩
        // ORA-01031 —— 那时用户已经等了一次全量基线。目标连接现在也带一项检查。
        ConnectionTestService service = serviceReturning(oracleTargetConnection(0L));
        ConnectionTestService.TestResult result = service.test(savedOracleTarget());
        assertThat(result.isSuccess()).isTrue();
        List<CdcProbeResult.Check> checks = result.getCdcChecks();
        assertThat(checks).isNotNull().hasSize(1);
        assertThat(checks.get(0).getName())
                .isEqualTo(CdcProbeResult.TARGET_DDL_PRIVILEGES);
        assertThat(checks.get(0).isPassed()).isFalse();
        assertThat(checks.get(0).getDetail()).contains("C##JYNC_MINER");
    }

    @Test
    void oracleTargetHoldingThePrivilegePasses() throws SQLException {
        ConnectionTestService service = serviceReturning(oracleTargetConnection(1L));
        ConnectionTestService.TestResult result = service.test(savedOracleTarget());
        assertThat(result.getCdcChecks()).hasSize(1);
        assertThat(result.getCdcChecks().get(0).isPassed()).isTrue();
    }

    @Test
    void unsupportedSourceTypeHasNoCdcChecks() throws SQLException {
        Connection conn = healthyMySqlConnection();
        ConnectionTestService service = serviceReturning(conn);
        DatabaseConfig config = savedMysql();
        config.setType(DatabaseType.DB2);   // not in (MYSQL, ORACLE, POSTGRESQL) → no probe
        ConnectionTestService.TestResult result = service.test(config);
        assertThat(result.getCdcChecks()).isNull();
    }

    @Test
    void probeSqlFailureDoesNotFailTheTest() throws SQLException {
        Connection conn = healthyMySqlConnection();
        // 真实探测逐项 catch SQLException 并降级为失败项;要「整体炸掉」必须以
        // 非受检形态冒出(如驱动抛出的运行时包装),此时服务层降级为无检查项
        when(conn.createStatement()).thenThrow(new RuntimeException(new SQLException("boom")));
        ConnectionTestService service = serviceReturning(conn);
        ConnectionTestService.TestResult result = service.test(savedMysql());
        assertThat(result.isSuccess()).isTrue();
        // 探测整体炸掉 → 降级为无检查项,而不是让「测试连接」变红
        assertThat(result.getCdcChecks()).isNull();
    }
}
