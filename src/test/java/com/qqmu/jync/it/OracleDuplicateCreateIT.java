package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.service.sync.DdlExecutor;

/**
 * 「幂等建表」在真实 Oracle 上必须真的幂等，中文语言环境也不例外。
 *
 * <p>实测事故：目标 Oracle 对重名建表返回「ORA-00955: 名称已由现有对象使用」，而
 * {@code DdlExecutor} 的「已存在」判定走的是文案白名单（中文只认「已存在」「已被使用」）
 * 加 SQLState，两个都没命中 —— 于是结构同步每轮发的 {@code CREATE TABLE} 每轮都被当成
 * 真失败。失败会扣住该表的快照，下一轮检测器又把它当新表再发一次：变更日志里 480 行刷的
 * 都是同一句。
 *
 * <p>第一个用例是端到端证据（对着未修复的判定跑就是 RED）；第二个用例钉住判定赖以成立的
 * 前提：驱动给的是 vendor code 955 / SQLState 42000，而不是任何一个「重复」SQLState。
 * 镜像默认就带中文错误文案，所以这里不需要切 NLS —— 正好是这个 bug 需要的语言环境。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it; run explicitly with
 * {@code mvn test -Dtest=OracleDuplicateCreateIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleDuplicateCreateIT {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    @BeforeAll
    static void start() {
        ORACLE.start();
    }

    @AfterAll
    static void stop() {
        ORACLE.stop();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(), ORACLE.getUsername(),
                ORACLE.getPassword());
    }

    @Test
    void aSecondCreateForAnExistingTableIsTolerated() throws SQLException {
        dropQuietly("DROP TABLE PROBE_T PURGE");
        try (Connection conn = connect()) {
            DdlExecutor executor = new DdlExecutor(conn);
            assertThat(executor.executeIdempotentCreate("CREATE TABLE PROBE_T (ID NUMBER)"))
                    .as("第一次建表：真的建出来了")
                    .isTrue();
            assertThat(executor.executeIdempotentCreate("CREATE TABLE PROBE_T (ID NUMBER)"))
                    .as("第二次建表：表已存在就是想要的结果，不能抛异常")
                    .isFalse();
        }
        dropQuietly("DROP TABLE PROBE_T PURGE");
    }

    @Test
    void oracleReportsTheDuplicateAsVendorCode955() throws SQLException {
        dropQuietly("DROP TABLE PROBE_CODE PURGE");
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE PROBE_CODE (ID NUMBER)");

            assertThatThrownBy(() -> st.execute("CREATE TABLE PROBE_CODE (ID NUMBER)"))
                    .isInstanceOf(SQLException.class)
                    .as("中文文案命中不了白名单，判定只能靠这个 vendor code")
                    .satisfies(e -> {
                        SQLException sql = (SQLException) e;
                        assertThat(sql.getErrorCode()).isEqualTo(955);
                        assertThat(sql.getSQLState()).isEqualTo("42000");
                    });
        }
        dropQuietly("DROP TABLE PROBE_CODE PURGE");
    }

    /** Cleanup only: the first call of a test has nothing to drop. */
    private static void dropQuietly(String sql) {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            // 表不存在
        }
    }
}
