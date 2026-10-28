package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v1.6.0 LogMiner readiness probe. The 7 checks (6 hard + 1 pass-with-warning) run
 * against a mocked JDBC connection; each test flips exactly one lever on the happy-path
 * rig and asserts only that check fails — matching the BinlogCdcProbeTest style.
 */
class LogMinerCdcProbeTest {

    private final LogMinerCdcProbe probe = new LogMinerCdcProbe();
    private Connection conn;
    private Statement stmt;
    private PreparedStatement ps;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        ps = mock(PreparedStatement.class);

        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Oracle");
        when(md.getDatabaseProductVersion()).thenReturn("23.0.0.0.0");
        when(conn.getMetaData()).thenReturn(md);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        // Happy path: ARCHIVELOG + supplemental + 12c LOGMINING role + no LONG columns.
        // One thenAnswer keyed by SQL content — avoids "un-stubbed SQL returned null" NPEs
        // when a test flips the product/version and the probe runs the 11g vs 12c branch.
        when(stmt.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("LOG_MODE")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn("ARCHIVELOG");
            } else if (sql.contains("SUPPLEMENTAL_LOG_DATA_MIN")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn("YES");
            } else if (sql.contains("SESSION_ROLES")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getLong(1)).thenReturn(1L);
            } else if (sql.contains("SESSION_PRIVS")) {
                // Happy path holds SELECT ANY TRANSACTION; tests that flip it stub the
                // exact query themselves (or rely on this fallback answering false).
                when(rs.next()).thenReturn(true, false);
                when(rs.getLong(1)).thenReturn(1L);
            } else {
                when(rs.next()).thenReturn(false);
            }
            return rs;
        });

        // Per-table checks: ALL_LOG_GROUPS has 1 row; ALL_TAB_COLUMNS has none.
        when(ps.executeQuery()).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            String sql = lastPreparedSql;
            if (sql != null && sql.contains("ALL_LOG_GROUPS")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getLong(1)).thenReturn(1L);
            } else {
                // ALL_TAB_COLUMNS — no LONG columns.
                when(rs.next()).thenReturn(false);
            }
            return rs;
        });
    }

    private String lastPreparedSql = "";

    @BeforeEach
    void capturePrepared() throws SQLException {
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            lastPreparedSql = inv.getArgument(0);
            return ps;
        });
    }

    /** Override a single SQL on the shared stmt mock with a 1-row string result. */
    private static void singleString(Statement st, String sql, String value) throws SQLException {
        when(st.executeQuery(sql)).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(value);
            return rs;
        });
    }

    /** Override a single SQL on the shared stmt mock with a 1-row long result. */
    private static void singleLong(Statement st, String sql, long value) throws SQLException {
        when(st.executeQuery(sql)).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong(1)).thenReturn(value);
            return rs;
        });
    }

    private static List<String[]> tables(String... ownerTablePairs) {
        List<String[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < ownerTablePairs.length; i += 2) {
            out.add(new String[]{ownerTablePairs[i], ownerTablePairs[i + 1]});
        }
        return out;
    }

    // —— tests ——

    @Test
    void allGreenOnCompliant12c() {
        CdcProbeResult r = probe.probe(conn, tables("SRC", "T1"));
        assertThat(r.passed()).isTrue();
        assertThat(r.getChecks()).hasSize(7);
    }

    /**
     * v1.9.0: the probe is picked by capability, not by product name, so an Oracle-mode
     * look-alike that answers CURRENT_SCN is still refused — by name, until its redo format
     * has been measured. (DM is no longer refused: it answers the Oracle probe with an
     * error and is then served by its own dialect — see LogMinerCdcProbeDmTest.)
     *
     * <p>The refusal is the WHOLE report: the remaining checks ask Oracle questions the
     * product cannot answer, and their remediation text ("enable supplemental logging")
     * would send a DBA down a path that cannot work.
     */
    @Test
    void anOracleModeLookAlikeIsRefusedAtTheSourceCheckAlone() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("OceanBase");
        when(md.getDatabaseProductVersion()).thenReturn("4.2.1");
        when(conn.getMetaData()).thenReturn(md);

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        assertThat(r.passed()).isFalse();
        assertThat(r.getChecks()).hasSize(1);
        CdcProbeResult.Check c = byName(r, CdcProbeResult.ORACLE_SOURCE);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail())
                .contains("product=OceanBase")
                .contains("refused");
    }

    /**
     * YashanDB answers the Oracle probe ({@code V$DATABASE.CURRENT_SCN} exists) but ships
     * no {@code SYS.DBMS_LOGMNR} and no {@code V$LOGMNR_CONTENTS} — measured 2026-10-29 on
     * 23.4.7.100 (see docs/dm-logminer-spike.md). The refusal must say that, because
     * "not measured yet" would imply a configuration change could unlock it.
     */
    @Test
    void yashanDbIsRefusedBecauseItHasNoMiningPackage() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("YashanDB");
        when(md.getDatabaseProductVersion()).thenReturn("23.4.7.100");
        when(conn.getMetaData()).thenReturn(md);

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());

        assertThat(r.passed()).isFalse();
        assertThat(r.getChecks()).hasSize(1);
        CdcProbeResult.Check c = byName(r, CdcProbeResult.ORACLE_SOURCE);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail())
                .contains("product=YashanDB")
                .contains("DBMS_LOGMNR")
                .contains("polling");
    }

    @Test
    void versionBelow11Fails() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Oracle");
        when(md.getDatabaseProductVersion()).thenReturn("10.2.0.5");
        when(conn.getMetaData()).thenReturn(md);
        // Major=10 → 11g branch; the setUp stub registered the 12c SQL, not the 11g SQL,
        // so register the 11g role query here.
        singleLong(stmt, "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN"
                + " ('EXECUTE_CATALOG_ROLE','SELECT_CATALOG_ROLE','DBA')", 1L);

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        CdcProbeResult.Check c = byName(r, CdcProbeResult.VERSION);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail()).contains("11.2");
    }

    @Test
    void noarchivelogFailsWithAlterText() throws SQLException {
        singleString(stmt, "SELECT LOG_MODE FROM V$DATABASE", "NOARCHIVELOG");

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        CdcProbeResult.Check c = byName(r, CdcProbeResult.ARCHIVELOG);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail()).contains("ALTER DATABASE ARCHIVELOG");
    }

    @Test
    void supplementalDbNoFailsWithAlter() throws SQLException {
        singleString(stmt, "SELECT SUPPLEMENTAL_LOG_DATA_MIN FROM V$DATABASE", "NO");

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        CdcProbeResult.Check c = byName(r, CdcProbeResult.SUPPLEMENTAL_DB);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail()).contains("ALTER DATABASE ADD SUPPLEMENTAL LOG DATA");
    }

    @Test
    void missingAllColumnLoggingFailsWithAlterStatement() throws SQLException {
        // Flip the ALL_LOG_GROUPS count to 0 for T1.
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            lastPreparedSql = inv.getArgument(0);
            PreparedStatement localPs = mock(PreparedStatement.class);
            when(localPs.executeQuery()).thenAnswer(inner -> {
                ResultSet rs = mock(ResultSet.class);
                if (lastPreparedSql.contains("ALL_LOG_GROUPS")) {
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getLong(1)).thenReturn(0L);
                } else {
                    when(rs.next()).thenReturn(false);
                }
                return rs;
            });
            return localPs;
        });

        CdcProbeResult r = probe.probe(conn, tables("SRC", "T1"));
        assertThat(r.passed()).isFalse();
        CdcProbeResult.Check c = byName(r, CdcProbeResult.SUPPLEMENTAL_TABLES);
        assertThat(c.getDetail()).contains("ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
        assertThat(c.getDetail()).contains("SRC.T1");
    }

    @Test
    void elevenGUsesCatalogRoleBranch() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Oracle");
        when(md.getDatabaseProductVersion()).thenReturn("11.2.0.4");
        when(conn.getMetaData()).thenReturn(md);
        // 11g branch expects EXECUTE_CATALOG_ROLE in SESSION_ROLES.
        singleLong(stmt, "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN"
                + " ('EXECUTE_CATALOG_ROLE','SELECT_CATALOG_ROLE','DBA')", 1L);

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        CdcProbeResult.Check c = byName(r, CdcProbeResult.MINING_PRIVILEGES);
        assertThat(c.isPassed()).isTrue();
        assertThat(c.getDetail()).contains("EXECUTE_CATALOG_ROLE");
    }

    @Test
    void elevenGRequiresSelectAnyTransactionForLogmnrContents() throws SQLException {
        // 11.2.0.2 实测:即便同时持有 EXECUTE_CATALOG_ROLE + SELECT_CATALOG_ROLE +
        // 直接的 SELECT ON V_$LOGMNR_CONTENTS,START_LOGMNR 之后读 V$LOGMNR_CONTENTS
        // 仍然报 ORA-01031;授予 SELECT ANY TRANSACTION 后才返回数据(DBA 角色自带该
        // 权限,普通挖掘账号不带)。只查角色会把一个"首轮挖掘必失败"的源误报成就绪。
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("Oracle");
        when(md.getDatabaseProductVersion()).thenReturn("11.2.0.2");
        when(conn.getMetaData()).thenReturn(md);
        singleLong(stmt, "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN"
                + " ('EXECUTE_CATALOG_ROLE','SELECT_CATALOG_ROLE','DBA')", 1L);
        // Role present, privilege absent — the customer's situation.
        singleLong(stmt, "SELECT COUNT(*) FROM SESSION_PRIVS"
                + " WHERE PRIVILEGE = 'SELECT ANY TRANSACTION'", 0L);

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        CdcProbeResult.Check c = byName(r, CdcProbeResult.MINING_PRIVILEGES);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail()).contains("SELECT ANY TRANSACTION");
    }

    @Test
    void miningPrivilegesMissing() throws SQLException {
        singleLong(stmt, "SELECT COUNT(*) FROM SESSION_ROLES WHERE ROLE IN ('LOGMINING','DBA')", 0L);

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        CdcProbeResult.Check c = byName(r, CdcProbeResult.MINING_PRIVILEGES);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail()).contains("LOGMINING");
    }

    @Test
    void longColumnsWarnButPass() throws SQLException {
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            lastPreparedSql = inv.getArgument(0);
            PreparedStatement localPs = mock(PreparedStatement.class);
            when(localPs.executeQuery()).thenAnswer(inner -> {
                ResultSet rs = mock(ResultSet.class);
                if (lastPreparedSql.contains("ALL_LOG_GROUPS")) {
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getLong(1)).thenReturn(1L);
                } else {
                    // ALL_TAB_COLUMNS → 1 LONG row.
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString(1)).thenReturn("BODY");
                }
                return rs;
            });
            return localPs;
        });

        CdcProbeResult r = probe.probe(conn, tables("SRC", "T2"));
        // LONG_COLUMNS is pass-with-warning.
        CdcProbeResult.Check c = byName(r, CdcProbeResult.LONG_COLUMNS);
        assertThat(c.isPassed()).isTrue();
        assertThat(c.getDetail()).contains("SRC.T2.BODY");
        // Overall probe still passes (warning, not blocker).
        assertThat(r.passed()).isTrue();
    }

    @Test
    void sqlExceptionDegradesToFailedCheckNotThrow() throws SQLException {
        when(stmt.executeQuery("SELECT LOG_MODE FROM V$DATABASE"))
                .thenThrow(new SQLException("ORA-00942: table or view not found"));

        CdcProbeResult r = probe.probe(conn, Collections.emptyList());
        assertThat(r.passed()).isFalse();
        CdcProbeResult.Check c = byName(r, CdcProbeResult.ARCHIVELOG);
        assertThat(c.getDetail()).contains("ORA-00942");
    }

    // —— P0-2:CDB 挖掘模式检查(仅 CDB 时出现,非 CDB 报告不变) ——

    private void stubCdb(String cdb, String conId) throws SQLException {
        singleString(stmt, "SELECT CDB FROM V$DATABASE", cdb);
        singleString(stmt, "SELECT SYS_CONTEXT('USERENV', 'CON_ID') FROM DUAL", conId);
    }

    private static com.qqmu.jync.model.DatabaseConfig configWithMiner(String user) {
        com.qqmu.jync.model.DatabaseConfig c = new com.qqmu.jync.model.DatabaseConfig();
        c.setMinerUsername(user);
        c.setMinerPassword(user == null ? null : "enc");
        return c;
    }

    @Test
    void cdbPdbConnectionWithoutMinerAccountFailsCheck() throws SQLException {
        // README 早已承诺 CDB 走公共挖掘账号;未配置时首轮 START_LOGMNR 必吃
        // ORA-65040。probe 必须提前拦住并点名解药。
        stubCdb("YES", "3");
        CdcProbeResult r = probe.probe(conn, Collections.emptyList(), configWithMiner(null));
        CdcProbeResult.Check c = byName(r, CdcProbeResult.CDB_MINING_MODE);
        assertThat(c.isPassed()).isFalse();
        assertThat(c.getDetail()).contains("ORA-65040").contains("miner");
    }

    @Test
    void cdbPdbConnectionWithMinerAccountPassesCheck() throws SQLException {
        stubCdb("YES", "3");
        CdcProbeResult r = probe.probe(conn, Collections.emptyList(),
                configWithMiner("C##MINER"));
        CdcProbeResult.Check c = byName(r, CdcProbeResult.CDB_MINING_MODE);
        assertThat(c.isPassed()).isTrue();
        assertThat(c.getDetail()).contains("C##MINER");
    }

    @Test
    void cdbRootConnectionPassesWithScopeNote() throws SQLException {
        // 直连 CDB$ROOT:能挖,但只挖 root 容器对象——通过,文案说清捕获范围。
        stubCdb("YES", "1");
        CdcProbeResult r = probe.probe(conn, Collections.emptyList(), configWithMiner(null));
        CdcProbeResult.Check c = byName(r, CdcProbeResult.CDB_MINING_MODE);
        assertThat(c.isPassed()).isTrue();
        assertThat(c.getDetail()).containsIgnoringCase("root");
    }

    @Test
    void nonCdbEmitsNoCdbCheck() {
        // 守护:默认 rig(V$DATABASE.CDB 查询返回空)不出现第八个检查项,报告形状不变。
        CdcProbeResult r = probe.probe(conn, tables("SRC", "T1"));
        assertThat(r.getChecks())
                .noneMatch(c -> c.getName().equals(CdcProbeResult.CDB_MINING_MODE));
    }

    private static CdcProbeResult.Check byName(CdcProbeResult r, String name) {
        return r.getChecks().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name
                        + " in " + Arrays.toString(
                                r.getChecks().stream().map(CdcProbeResult.Check::getName).toArray())));
    }
}
