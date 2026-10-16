package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.connection.TargetDdlProbe;

/**
 * The target-side "can this account create tables?" pre-check, on real Oracle 23ai Free.
 *
 * <p>Everything here exists to keep the probe honest about ORA-01031: the verdict it reports
 * is compared against what Oracle actually does when the same {@code CREATE TABLE} runs. The
 * second test is the one that constrains the implementation — a {@code C##} common user
 * granted {@code CREATE TABLE} in {@code CDB$ROOT} is <b>reported as granted by
 * {@code DBA_SYS_PRIVS}</b> yet really fails inside a PDB, so a probe built on the catalog
 * views would hand the user a green light and the same ORA-01031 they reported.
 *
 * <p>Named {@code *IT} so surefire's default includes skip it; run explicitly with
 * {@code mvn test -Dtest=TargetDdlProbeIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetDdlProbeIT {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    private static final String PASSWORD = "Jync12345";

    private final TargetDdlProbe probe = new TargetDdlProbe();

    private static String pdb;

    @BeforeAll
    static void start() throws SQLException {
        ORACLE.start();
        try (Connection admin = admin();
             Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV','CON_NAME') FROM DUAL")) {
            pdb = rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * A fresh privileged session, so no test inherits another's container switch.
     *
     * <p>{@code OracleContainer.getUsername()} is the image's plain {@code APP_USER}
     * ({@code test}) — it can create tables but not users, which is why the setup goes
     * through {@code system} with the same password the container was started with.
     */
    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static DatabaseConfig targetConfig(String username) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("tgt");
        config.setType(DatabaseType.ORACLE);
        config.setUsername(username);
        return config;
    }

    /** The single verdict the probe returns for this account. */
    private CdcProbeResult.Check checkFor(String username) throws SQLException {
        try (Connection conn = DriverManager.getConnection(ORACLE.getJdbcUrl(),
                username, PASSWORD)) {
            List<CdcProbeResult.Check> checks = probe.probe(conn, targetConfig(username));
            assertThat(checks).hasSize(1);
            assertThat(checks.get(0).getName())
                    .isEqualTo(CdcProbeResult.TARGET_DDL_PRIVILEGES);
            return checks.get(0);
        }
    }

    /** Runs the DDL the sync itself would run, to see whether the verdict matched reality. */
    private static void createRealTable(String username, String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection(ORACLE.getJdbcUrl(), username, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + table + " (id NUMBER(19) NOT NULL,"
                    + " name VARCHAR2(50 CHAR), PRIMARY KEY (id))");
        }
    }

    @Test
    void aPlainUserWithoutCreateTableIsReportedBeforeTheDdlFails() throws Exception {
        try (Connection admin = admin()) {
            exec(admin, "CREATE USER jync_nopriv IDENTIFIED BY \"" + PASSWORD + "\"");
            exec(admin, "GRANT CREATE SESSION TO jync_nopriv");
        }

        CdcProbeResult.Check before = checkFor("jync_nopriv");
        assertThat(before.isPassed())
                .as("只有 CREATE SESSION 的账号建表必然失败,预检必须说失败")
                .isFalse();
        // 账号名按 Oracle 的答案上折(GRANT 里照抄的就是这个名字),不是表单里的原样。
        assertThat(before.getDetail()).contains("JYNC_NOPRIV").contains("CREATE TABLE")
                .contains("ORA-01031");
        // 预检说的是不是真的:同一条 DDL 真跑一次。
        assertThatThrownBy(() -> createRealTable("jync_nopriv", "JYNC_NOPRIV"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-01031");

        try (Connection admin = admin()) {
            exec(admin, "GRANT CREATE TABLE TO jync_nopriv");
            exec(admin, "ALTER USER jync_nopriv QUOTA UNLIMITED ON USERS");
        }

        assertThat(checkFor("jync_nopriv").isPassed())
                .as("授权之后同一个账号必须变成通过")
                .isTrue();
        createRealTable("jync_nopriv", "JYNC_NOPRIV");
    }

    @Test
    void aCommonUserGrantedOnlyInRootFailsInsideThePdb() throws Exception {
        try (Connection admin = admin()) {
            exec(admin, "ALTER SESSION SET CONTAINER = CDB$ROOT");
            exec(admin, "CREATE USER C##JYNC_COMMON IDENTIFIED BY \"" + PASSWORD + "\"");
            exec(admin, "GRANT CREATE SESSION TO C##JYNC_COMMON CONTAINER=ALL");
            // 关键:只在 root 里授 CREATE TABLE。DBA_SYS_PRIVS 会报成"已授权"。
            exec(admin, "GRANT CREATE TABLE TO C##JYNC_COMMON");
            exec(admin, "ALTER SESSION SET CONTAINER = " + pdb);
        }

        CdcProbeResult.Check before = checkFor("C##JYNC_COMMON");
        assertThat(before.isPassed())
                .as("root 里的授权在 PDB 内不生效,预检不许给绿灯 —— 这正是当初选用"
                        + " SESSION_PRIVS 而不是 DBA_SYS_PRIVS 的原因")
                .isFalse();
        assertThat(before.getDetail())
                .as("公共用户的失败提示必须点明授权要在 PDB 内执行")
                .contains("CONTAINER");
        assertThatThrownBy(() -> createRealTable("C##JYNC_COMMON", "JYNC_COMMON_T"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-01031");

        // 在目标 PDB 内补授权(与失败提示里给的路径一致)。
        try (Connection admin = admin()) {
            exec(admin, "GRANT CREATE TABLE TO C##JYNC_COMMON");
            exec(admin, "ALTER USER C##JYNC_COMMON QUOTA UNLIMITED ON USERS");
        }

        assertThat(checkFor("C##JYNC_COMMON").isPassed()).isTrue();
        createRealTable("C##JYNC_COMMON", "JYNC_COMMON_T");
    }
}
