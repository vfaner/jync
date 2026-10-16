package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.task.SyncTaskRunner;

/**
 * 生产实测的回归:源库一个字没改，MySQL → Oracle 的轮询项目却每轮都刷一条 updated。
 *
 * <p>游标窗口带 1 秒安全余量（防迟到提交，设计如此），于是尾巴那批行每轮都被重发一次。
 * 重发本身没问题 —— 值一样，目标写不写都是同一个结果；问题是「算不算改动」：MySQL 目标报
 * 0 影响行，引擎跳过；Oracle 的 MERGE 报 1，落进「分不清插入还是更新」那一类，被记成
 * updated，于是日志不停、界面不停刷新。这里跑的是完整链路：真的 MySQL 源、真的 Oracle 目标、
 * 真的轮询调度入口，源数据全程不动。
 */
class MysqlToOracleIdleRepollIT extends CdcMySqlContainerHarnessIT {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    private static final String TABLE = "idle_orders";

    @Autowired private SyncTaskRunner runner;

    @BeforeEach
    void startOracle() {
        if (!ORACLE.isRunning()) {
            ORACLE.start();
        }
    }

    @Override
    protected Connection targetJdbc() throws SQLException {
        startOracle();
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }

    @Override
    protected DatabaseConfig targetConfig() {
        startOracle();
        DatabaseConfig config = new DatabaseConfig();
        config.setName("idle-it-ora-tgt");
        config.setType(DatabaseType.ORACLE);
        config.setRole(ConnectionRole.TARGET);
        config.setHost(ORACLE.getHost());
        config.setPort(ORACLE.getMappedPort(1521));
        config.setDatabaseName(ORACLE.getDatabaseName());
        config.setSchemaName(ORACLE.getUsername().toUpperCase(java.util.Locale.ROOT));
        config.setUsername(ORACLE.getUsername());
        config.setPassword(cryptoUtil.encrypt(ORACLE.getPassword()));
        return databaseConfigRepository.save(config);
    }

    @Test
    void anIdlePollingCycleReportsNoChanges() throws Exception {
        seedSource();
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        config.getTables().add(TABLE);
        config.getCursorColumns().put(TABLE, "updated_at");
        Project project = newProject("idle-repoll", config, false);
        try {
            SyncResult initial = run(project);
            assertThat(initial.getErrors()).as("首轮基线不该报错").isEmpty();
            assertThat(initial.getRowsInserted()).as("首轮把两行搬过去").isEqualTo(2);

            SyncResult idle = run(project);
            assertThat(idle.getErrors()).as("第二轮不该报错").isEmpty();
            // 源库没动:窗口的安全余量把这两行重发了一遍,但它们与目标里的值一模一样,
            // 所以一行都不该算作改动 —— 这正是用户看到的「不停一致更新」。
            assertThat(idle.getRowsUpdated()).as("源库没动，不该报更新").isZero();
            assertThat(idle.getRowsInserted()).isZero();
            assertThat(countTarget(TABLE)).as("目标行数不变").isEqualTo(2);
        } finally {
            disableProject(project);
            dropTarget();
        }
    }

    /** 源表两行,带游标列;时间戳故意固定在过去 —— 水位线来自数据,与墙上时钟无关。 */
    private void seedSource() throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, name VARCHAR(50),"
                    + " updated_at DATETIME(3))");
            st.execute("INSERT INTO " + TABLE + " VALUES"
                    + " (1, 'ann', '2026-01-01 10:00:00.000'),"
                    + " (2, 'bob', '2026-01-01 10:00:00.000')");
        }
    }

    private SyncResult run(Project project) {
        return runner.runOnce(project.getId()).result()
                .orElseThrow(() -> new AssertionError("轮询没有执行:项目被别的周期占着?"));
    }

    private void dropTarget() throws SQLException {
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"" + TABLE.toUpperCase()
                    + "\" CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;");
        }
    }
}
