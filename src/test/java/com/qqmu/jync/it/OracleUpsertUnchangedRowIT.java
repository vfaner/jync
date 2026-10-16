package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.service.converter.OracleDialect;

/**
 * docker 实测:空闲的 MySQL→Oracle 项目每轮都刷一条 updated 日志的根因。
 *
 * <p>游标窗口的 1 秒安全余量每轮重放同一批行（防迟到提交，设计如此）。MySQL 目标对「值
 * 一样」的 upsert 报 0 影响行，引擎跳过它；Oracle 的 MERGE 报 1，落在「分不清插入还是
 * 更新」那一类里被记成 updated —— 源库一个字没改，日志却在刷。这里跑的是方言生成的那条
 * MERGE 语句本身，钉住「加了差异条件之后，无变化的重放报 0 影响行」。
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleUpsertUnchangedRowIT {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    private static final List<String> COLUMNS = List.of("ID", "NAME", "AMOUNT");
    private static final List<String> PK = List.of("ID");

    @BeforeAll
    static void start() throws SQLException {
        ORACLE.start();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            dropQuietly(st);
            st.execute("CREATE TABLE UPSERT_T (ID NUMBER PRIMARY KEY, NAME VARCHAR2(20),"
                    + " AMOUNT NUMBER(10,2))");
        }
    }

    @AfterAll
    static void stop() throws SQLException {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            dropQuietly(st);
        }
        ORACLE.stop();
    }

    @Test
    void aReplayedRowWithIdenticalValuesAffectsNothing() throws SQLException {
        assertThat(upsert(1, "ann", 10.5)).as("首次插入").isEqualTo(1);
        assertThat(upsert(1, "ann", 10.5))
                .as("值一个字没变的重放，必须报 0 —— 否则每轮日志都有一条假的 updated")
                .isZero();
    }

    @Test
    void aRealChangeIsStillWritten() throws SQLException {
        assertThat(upsert(2, "bob", 1.0)).isEqualTo(1);
        assertThat(upsert(2, "bob", 2.0)).as("金额变了").isEqualTo(1);
        assertThat(upsert(2, "bobby", 2.0)).as("名字变了").isEqualTo(1);
    }

    @Test
    void aValueAppearingOrDisappearingCountsAsAChange() throws SQLException {
        // 差异条件必须 NULL 安全:偷懒写 a <> b 的话，NULL→值、值→NULL 都被判成「没变」，
        // 真实变化会被静默丢掉 —— 那比多报日志严重得多。
        assertThat(upsert(3, null, 1.0)).isEqualTo(1);
        assertThat(upsert(3, "now-set", 1.0)).as("NULL → 值").isEqualTo(1);
        assertThat(upsert(3, null, 1.0)).as("值 → NULL").isEqualTo(1);
        assertThat(upsert(3, null, 1.0)).as("两边都是 NULL").isZero();
    }

    /**
     * 设计前提:C LOB 列不能用 = / <> 比较。所以「值没变就不写」这个条件到底能不能挂,
     * 只能看目标列的真实类型 —— 光看绑定类型不行,源端 VARCHAR 落在目标 CLOB 上是这个项目
     * 明确支持的形态(TargetTableVerifier 有对应用例)。
     */
    @Test
    void comparingAClobColumnIsRejectedByOracle() throws SQLException {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE CLOB_T PURGE';"
                    + " EXCEPTION WHEN OTHERS THEN NULL; END;");
            st.execute("CREATE TABLE CLOB_T (ID NUMBER PRIMARY KEY, NOTE CLOB)");
            try {
                st.execute("SELECT 1 FROM CLOB_T WHERE NOTE <> NOTE");
                throw new AssertionError("Oracle 居然允许比较 CLOB,这条设计前提不成立了");
            } catch (SQLException e) {
                // 实测是 ORA-22848 "cannot be compared"(两个 LOB 列互比);与绑定值比时
                // Oracle 报的是 ORA-00932。两种都说明这条路径不可用。
                assertThat(e.getErrorCode()).as("Oracle 必须拒绝比较 CLOB").isIn(932, 22848);
            } finally {
                st.execute("DROP TABLE CLOB_T PURGE");
            }
        }
    }

    /** A MERGE that matched a row and changed nothing must report 0 rows, like MySQL's upsert. */
    private static int upsert(int id, String name, double amount) throws SQLException {
        Map<String, Integer> types = new HashMap<>();
        types.put("ID", Types.NUMERIC);
        types.put("NAME", Types.VARCHAR);
        types.put("AMOUNT", Types.NUMERIC);
        // 生产调用方会先读目标表字典,确认每一列都能用 = / <> 比较,再传 true
        // (TargetTableVerifier.uncomparableColumns);这里三列都是 NUMBER/VARCHAR2,故为 true。
        String sql = new OracleDialect().getUpsertSql(null, "UPSERT_T", COLUMNS, PK, types, true);
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            if (name == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, name);
            }
            ps.setBigDecimal(3, java.math.BigDecimal.valueOf(amount));
            return ps.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(), ORACLE.getUsername(),
                ORACLE.getPassword());
    }

    private static void dropQuietly(Statement st) {
        try {
            st.execute("DROP TABLE UPSERT_T PURGE");
        } catch (SQLException didNotExist) {
            // 首次运行没有这张表
        }
    }
}
