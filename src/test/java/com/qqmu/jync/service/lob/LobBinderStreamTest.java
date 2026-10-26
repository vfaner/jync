package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;

/**
 * How a {@code LobValue} reaches the target driver. Oracle cannot take a streamed value in
 * a MERGE (the placeholder is a LONG), so it must be copied into a temporary CLOB/BLOB
 * first; everyone else gets the reader directly and the driver reads it at execute time.
 */
class LobBinderStreamTest {

    private static StringWriter writerFor(Clob clob) throws Exception {
        StringWriter writer = new StringWriter();
        when(clob.setCharacterStream(1L)).thenReturn(writer);
        return writer;
    }

    @Test
    void anEmptyStringIsLeftToTheDriverWhenTheRescueIsSwitchedOff() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        when(ps.getConnection()).thenReturn(conn);

        // With sync.lob.oracle-empty-string-as-empty-clob=false the caller binds '' itself
        // and Oracle stores its usual NULL; no temporary CLOB may be created.
        assertThat(LobBinder.bind(ps, 1, "", Types.CLOB,
                new OracleDialect().withEmptyStringAsLob(false), LobBinder.NOOP)).isFalse();
        verify(conn, never()).createClob();
    }

    @Test
    void oracleCopiesTextIntoATemporaryClob() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Clob clob = mock(Clob.class);
        LobBinder.Tracker tracker = mock(LobBinder.Tracker.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createClob()).thenReturn(clob);
        StringWriter writer = writerFor(clob);

        String text = "x".repeat(40_000);
        LobValue value = LobValues.oneShotText(new StringReader(text), text.length());

        assertThat(LobBinder.bind(ps, 1, value, Types.CLOB, new OracleDialect(), tracker))
                .isTrue();

        assertThat(writer.toString()).hasSize(40_000);
        verify(ps).setClob(1, clob);
        verify(tracker).track(clob);
    }

    @Test
    void oracleCopiesBinaryIntoATemporaryBlob() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        Blob blob = mock(Blob.class);
        LobBinder.Tracker tracker = mock(LobBinder.Tracker.class);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createBlob()).thenReturn(blob);
        when(blob.setBinaryStream(1L)).thenReturn(copied);

        LobValue value = LobValues.oneShotBinary(
                new java.io.ByteArrayInputStream(new byte[33_000]), 33_000);

        assertThat(LobBinder.bind(ps, 1, value, Types.BLOB, new OracleDialect(), tracker))
                .isTrue();

        assertThat(copied.size()).isEqualTo(33_000);
        verify(ps).setBlob(1, blob);
        verify(tracker).track(blob);
    }

    @Test
    void postgresGetsTheReaderWithItsKnownLength() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        LobValue value = LobValues.text("hello");

        assertThat(LobBinder.bind(ps, 1, value, Types.VARCHAR, new PostgresDialect(),
                LobBinder.NOOP)).isTrue();

        verify(ps).setCharacterStream(eq(1), any(Reader.class), eq(5));
    }

    @Test
    void unknownLengthFallsBackToTheLengthlessSetter() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        LobValue value = LobValues.oneShotText(new StringReader("abc"), -1);

        assertThat(LobBinder.bind(ps, 1, value, Types.CLOB, new PostgresDialect(),
                LobBinder.NOOP)).isTrue();

        verify(ps).setCharacterStream(eq(1), any(Reader.class));
    }

    @Test
    void aKnownLengthUsesTheIntSetterBecausePgJdbcDoesNotImplementTheLongOne() throws Exception {
        // org.postgresql / org.opengauss 的 PgPreparedStatement 对
        // setCharacterStream(int, Reader, long) 直接抛 notImplemented，只有 int 重载会真正
        // 读取 Reader。传 long 会让任何带回放长度的文本（CDC 落盘、SQL Server 源）写不进
        // PostgreSQL/openGauss 目标。
        PreparedStatement ps = mock(PreparedStatement.class);
        doThrow(new java.sql.SQLFeatureNotSupportedException(
                "setCharacterStream(int, Reader, long) is not implemented"))
                .when(ps).setCharacterStream(eq(1), any(Reader.class), anyLong());

        LobValue value = LobValues.text("hello");

        assertThat(LobBinder.bind(ps, 1, value, Types.VARCHAR, new PostgresDialect(),
                LobBinder.NOOP)).isTrue();

        verify(ps).setCharacterStream(eq(1), any(Reader.class), eq(5));
    }

    @Test
    void aReplayableValueCanBeBoundTwiceWithIdenticalContent() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        LobValue value = LobValues.text("repeat");

        assertThat(LobBinder.bind(ps, 1, value, Types.CLOB, new PostgresDialect(),
                LobBinder.NOOP)).isTrue();
        assertThat(LobBinder.bind(ps, 1, value, Types.CLOB, new PostgresDialect(),
                LobBinder.NOOP)).isTrue();

        ArgumentCaptor<Reader> readers = ArgumentCaptor.forClass(Reader.class);
        verify(ps, times(2)).setCharacterStream(eq(1), readers.capture(), eq(6));
        for (Reader reader : readers.getAllValues()) {
            try (Reader r = reader) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[16];
                int n;
                while ((n = r.read(buffer)) != -1) {
                    sb.append(buffer, 0, n);
                }
                assertThat(sb.toString()).isEqualTo("repeat");
            }
        }
    }

    @Test
    void mysqlBinaryGoesThroughSetBinaryStream() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        LobValue value = LobValues.binary(new byte[9000]);

        assertThat(LobBinder.bind(ps, 1, value, Types.BLOB, new MySqlDialect(),
                LobBinder.NOOP)).isTrue();

        verify(ps).setBinaryStream(eq(1), any(java.io.InputStream.class), eq(9000L));
    }

    @Test
    void ordinaryValuesStillFallThrough() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThat(LobBinder.bind(ps, 1, "small", Types.VARCHAR, new PostgresDialect(),
                LobBinder.NOOP)).isFalse();
        assertThat(LobBinder.bind(ps, 1, new byte[] {1}, Types.BLOB, new MySqlDialect(),
                LobBinder.NOOP)).isFalse();
    }
}
