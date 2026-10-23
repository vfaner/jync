package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.OracleDialect;

/**
 * Oracle 的服务端临时 LOB 占用的是目标库临时表空间，必须由 Jync 显式 {@code free()}。
 *
 * <p>旧实现只在 {@code setClob}/{@code setBlob} 成功之后才把句柄交给 tracker，因此任何
 * 中途失败（填充流抛错、写入中途断开、{@code setClob} 自身失败）都会把一个没人引用的
 * 临时 LOB 留在会话里，只能等连接关闭才回收。同步任务的重试循环会一遍遍重复这条路径，
 * 最终把临时表空间撑满（ORA-01652），表现为一个与本行数据无关的批处理失败。
 */
class LobBinderLeakTest {

    @Test
    void temporaryClobIsFreedWhenFillingItFails() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Clob clob = mock(Clob.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createClob()).thenReturn(clob);
        when(clob.setCharacterStream(1L)).thenThrow(new SQLException("ORA-01652"));

        LobValue value = LobValues.oneShotText(new StringReader("x"), 1);

        assertThatThrownBy(() -> LobBinder.bind(ps, 1, value, Types.CLOB,
                new OracleDialect(), LobBinder.newTracker()))
                .isInstanceOf(SQLException.class);
        verify(clob).free();
    }

    @Test
    void temporaryClobIsFreedWhenTheCopyFailsMidStream() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Clob clob = mock(Clob.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createClob()).thenReturn(clob);
        when(clob.setCharacterStream(1L)).thenReturn(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("connection reset");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        LobValue value = LobValues.oneShotText(new StringReader("hello"), 5);

        assertThatThrownBy(() -> LobBinder.bind(ps, 1, value, Types.CLOB,
                new OracleDialect(), LobBinder.newTracker()))
                .isInstanceOf(SQLException.class);
        verify(clob).free();
    }

    @Test
    void temporaryBlobIsFreedWhenFillingItFails() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Blob blob = mock(Blob.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createBlob()).thenReturn(blob);
        when(blob.setBinaryStream(1L)).thenThrow(new SQLException("ORA-01652"));

        LobValue value = LobValues.oneShotBinary(new java.io.ByteArrayInputStream(new byte[1]), 1);

        assertThatThrownBy(() -> LobBinder.bind(ps, 1, value, Types.BLOB,
                new OracleDialect(), LobBinder.newTracker()))
                .isInstanceOf(SQLException.class);
        verify(blob).free();
    }

    @Test
    void inlineTemporaryClobIsFreedWhenSetStringFails() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Clob clob = mock(Clob.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createClob()).thenReturn(clob);
        // Clob.setString takes a long position, so the matcher must be anyLong().
        org.mockito.Mockito.doThrow(new SQLException("ORA-01652"))
                .when(clob).setString(anyLong(), anyString());

        String text = "x".repeat(40_000); // 超过 Oracle 的 32767 字节内联上限

        assertThatThrownBy(() -> LobBinder.bind(ps, 1, text, Types.CLOB,
                new OracleDialect(), LobBinder.newTracker()))
                .isInstanceOf(SQLException.class);
        verify(clob).free();
    }

    @Test
    void successfulBindingLeavesTheLobToTheTracker() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Clob clob = mock(Clob.class);
        LobBinder.Tracker tracker = LobBinder.newTracker();
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createClob()).thenReturn(clob);
        when(clob.setCharacterStream(1L)).thenReturn(new java.io.StringWriter());

        LobValue value = LobValues.oneShotText(new StringReader("x"), 1);
        LobBinder.bind(ps, 1, value, Types.CLOB, new OracleDialect(), tracker);

        verify(clob, never()).free();
        tracker.releaseAll();
        verify(clob).free();
    }
}
