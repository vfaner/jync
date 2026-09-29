package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * 达梦 DM / 金仓 KingBase 等国产库的「对象已存在」错误是中文文案，
 * DdlExecutor 必须识别为良性（已存在）而不是当失败抛出。
 */
class DdlExecutorIdempotentCreateTest {

    /** 用一个会抛 SQLException 的 mock Statement，模拟驱动的中文「已存在」错误。 */
    private DdlExecutor executorThrowing(String message, String sqlState, int errorCode) throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.execute("CREATE TABLE X (id INT)")).thenThrow(
                new SQLException(message, sqlState, errorCode));
        return new DdlExecutor(conn);
    }

    @Test
    void dmChineseAlreadyExistsIsBenign() throws SQLException {
        // 达梦 DM 的典型报错：对象[table]已存在
        DdlExecutor exec = executorThrowing("对象[alarm record 1]已存在", null, -2106);
        // 不应抛异常，返回 false = 已存在，不阻塞数据同步
        assertThat(exec.executeIdempotentCreate("CREATE TABLE X (id INT)")).isFalse();
    }

    @Test
    void kingbaseChineseAlreadyExistsIsBenign() throws SQLException {
        DdlExecutor exec = executorThrowing("关系已存在", "42P07", 0);
        assertThat(exec.executeIdempotentCreate("CREATE TABLE X (id INT)")).isFalse();
    }

    @Test
    void englishAlreadyExistsIsBenign() throws SQLException {
        DdlExecutor exec = executorThrowing("Table already exists", "42S01", 0);
        assertThat(exec.executeIdempotentCreate("CREATE TABLE X (id INT)")).isFalse();
    }

    @Test
    void sqlserverAlreadyExistsIsBenign() throws SQLException {
        // SQL Server: There is already an object named 'X' in the database. (SQLState S0001)
        DdlExecutor exec = executorThrowing(
                "There is already an object named 'alarm record 1' in the database.", "S0001", 2714);
        assertThat(exec.executeIdempotentCreate("CREATE TABLE X (id INT)")).isFalse();
    }

    @Test
    void dmDropMissingTableIsBenign() throws SQLException {
        // 达梦 DROP 不存在的表：无效的表或视图名
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.execute("DROP TABLE X")).thenThrow(
                new SQLException("无效的表或视图名[T2]", "42S02", -2106));
        DdlExecutor exec = new DdlExecutor(conn);
        // tolerateMissing = true（DROP 容错）
        assertThat(exec.execute("DROP TABLE X", true)).isFalse();
    }

    @Test
    void realErrorIsNotBenign() throws SQLException {
        // 真正的语法错误 / 权限不足不能被吞
        DdlExecutor exec = executorThrowing("syntax error near CREATE", "42000", 1064);
        assertThatThrownBy(() -> exec.executeIdempotentCreate("CREATE TABLE X (id INT)"))
                .isInstanceOf(SQLException.class);
    }
}