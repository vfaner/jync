package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.CdcProbeResult;

/**
 * Target-side pre-flight: can the configured account actually create tables where the sync
 * will write? The failure it exists for is the one a live MySQL→Oracle run hit —
 * {@code CREATE TABLE "C##JYNC_MINER"."CLASS" ... -> ORA-01031: insufficient privileges},
 * discovered at DDL time because the connection test never checked write privileges.
 *
 * <p>The probe reads {@code SESSION_PRIVS}, which reports the privileges <b>enabled in this
 * session</b>. Measured on Oracle 23ai Free, that is the only view that tells the truth for
 * a CDB common user: a {@code C##…} account granted {@code CREATE TABLE} in {@code CDB$ROOT}
 * still reports 0 and still fails the real {@code CREATE TABLE} with ORA-01031 inside a PDB,
 * while {@code DBA_SYS_PRIVS} would have said "granted". Catalog-view walking would therefore
 * produce false passes — the exact failure mode this probe is meant to end.
 */
class TargetDdlProbeTest {

    private final TargetDdlProbe probe = new TargetDdlProbe();
    private Connection conn;
    private Statement stmt;

    /** What {@code SELECT USER} answers — the connection's own schema. */
    private String currentUser = "JYNC_TGT";
    /** Rows the SESSION_PRIVS count query returns. */
    private long privilegeCount = 1L;
    /** Every statement text the probe ran, in order. */
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Oracle");
        when(conn.getMetaData()).thenReturn(md);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executed.add(sql);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            if (sql.contains("USER FROM DUAL")) {
                when(rs.getString(1)).thenReturn(currentUser);
            } else {
                when(rs.getLong(1)).thenReturn(privilegeCount);
            }
            return rs;
        });
    }

    private DatabaseConfig target(String schemaName) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("tgt");
        config.setType(DatabaseType.ORACLE);
        config.setSchemaName(schemaName);
        return config;
    }

    private CdcProbeResult.Check onlyCheck(DatabaseConfig config) throws SQLException {
        List<CdcProbeResult.Check> checks = probe.probe(conn, config);
        assertThat(checks).hasSize(1);
        return checks.get(0);
    }

    @Test
    void missingCreateTablePrivilegeFailsAndNamesTheGrantToRun() throws Exception {
        currentUser = "C##JYNC_MINER";
        privilegeCount = 0L;

        CdcProbeResult.Check check = onlyCheck(target(null));

        assertThat(check.getName()).isEqualTo(CdcProbeResult.TARGET_DDL_PRIVILEGES);
        assertThat(check.isPassed()).isFalse();
        assertThat(check.getDetail())
                .as("失败提示必须给出可直接照抄的授权语句与账号名")
                .contains("C##JYNC_MINER")
                .contains("CREATE TABLE")
                .contains("GRANT");
        assertThat(check.getDetail())
                .as("公共用户(C##)的授权必须在目标 PDB 内执行,否则在 root 里授了也不生效")
                .contains("CONTAINER");
    }

    @Test
    void createTablePrivilegeOnTheConnectionsOwnSchemaPasses() throws Exception {
        currentUser = "JYNC_TGT";
        privilegeCount = 1L;

        CdcProbeResult.Check check = onlyCheck(target(null));

        assertThat(check.isPassed()).isTrue();
        assertThat(check.getDetail()).contains("JYNC_TGT");
        assertThat(executed).as("自有 schema 只要求 CREATE TABLE")
                .anyMatch(sql -> sql.contains("SESSION_PRIVS") && sql.contains("'CREATE TABLE'"));
    }

    @Test
    void aTargetSchemaOtherThanTheConnectionUserRequiresCreateAnyTable() throws Exception {
        currentUser = "JYNC_TGT";
        privilegeCount = 0L;

        CdcProbeResult.Check check = onlyCheck(target("APP_OWNER"));

        assertThat(check.isPassed()).isFalse();
        assertThat(check.getDetail()).contains("APP_OWNER").contains("CREATE ANY TABLE");
        assertThat(executed)
                .as("建到别人的 schema 下,Oracle 要的是 CREATE ANY TABLE 而不是 CREATE TABLE")
                .anyMatch(sql -> sql.contains("SESSION_PRIVS")
                        && sql.contains("'CREATE ANY TABLE'"));
    }

    @Test
    void aSchemaNameNamingTheConnectionUserStillOnlyNeedsCreateTable() throws Exception {
        // 配置里写了连接用户自己(大小写随意):SyncContextFactory 对 Oracle 家族会把配置的
        // schema 折成大写,所以这里必须大小写无关地判定成"自有 schema",否则会去要一个
        // 用户根本不需要的 CREATE ANY TABLE,把本来正常的账号判成失败。
        currentUser = "JYNC_TGT";
        privilegeCount = 1L;

        CdcProbeResult.Check check = onlyCheck(target("jync_tgt"));

        assertThat(check.isPassed()).isTrue();
        assertThat(executed).noneMatch(sql -> sql.contains("CREATE ANY TABLE"));
    }

    @Test
    void aLookAlikeProductIsNotProbedAtAll() throws Exception {
        // 目标侧只需要普通 DDL,方言兼容库(达梦/OceanBase Oracle 模式)照样能建表;
        // SESSION_PRIVS 在它们身上不存在,照着查会把好好的连接报成"没有建表权限"。
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("OceanBase");
        when(conn.getMetaData()).thenReturn(md);

        assertThat(probe.probe(conn, target(null))).isEmpty();
        assertThat(executed).isEmpty();
    }
}
