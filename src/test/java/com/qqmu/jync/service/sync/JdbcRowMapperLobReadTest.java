package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.lob.LobLimitExceededException;
import com.qqmu.jync.service.lob.LobReadPolicy;
import com.qqmu.jync.service.lob.LobSpillSession;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.lob.LobValue;

/**
 * The read side of LOB streaming: a column the policy says is large must never go through
 * {@code getString}/{@code getBytes}, because that is the call that materializes the whole
 * value in the heap. Small values still inline so bulk loads keep batching.
 */
class JdbcRowMapperLobReadTest {

    @TempDir
    Path dir;

    private static LobReadPolicy oneShot(long inlineMax) {
        return LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                inlineMax, 1 << 20, List.of());
    }

    private static String readAll(Reader reader) throws IOException {
        try (Reader r = reader) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[64];
            int n;
            while ((n = r.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
            return sb.toString();
        }
    }

    private static Map<String, Object> read(ResultSet rs, int jdbcType, String typeName,
                                            LobReadPolicy policy, LobSpillSession session)
            throws Exception {
        return JdbcRowMapper.readRowInto(rs, List.of("body"), List.of(jdbcType),
                List.of(typeName), DatabaseType.MYSQL, new LinkedHashMap<>(), policy, session);
    }

    @Test
    void largeClobIsStreamedAndGetStringIsNeverCalled() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getCharacterStream(1)).thenReturn(new StringReader("x".repeat(100)));

        Object value = read(rs, Types.CLOB, "CLOB", oneShot(8), null).get("body");

        assertThat(value).isInstanceOf(LobValue.class);
        LobValue lob = (LobValue) value;
        assertThat(lob.kind()).isEqualTo(LobValue.Kind.TEXT);
        assertThat(lob.replayable()).isFalse();
        assertThat(readAll(lob.openReader())).hasSize(100);
        verify(rs, never()).getString(anyInt());
        verify(rs, never()).getBytes(anyInt());
        lob.release();
    }

    @Test
    void aSmallValueStillComesBackInline() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getCharacterStream(1)).thenReturn(new StringReader("tiny"));

        Object value = read(rs, Types.CLOB, "CLOB", oneShot(64), null).get("body");

        assertThat(value).isEqualTo("tiny");
        verify(rs, never()).getString(anyInt());
    }

    @Test
    void largeBlobIsStreamedAndGetBytesIsNeverCalled() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBinaryStream(1)).thenReturn(new ByteArrayInputStream(new byte[64]));

        Object value = read(rs, Types.BLOB, "BLOB", oneShot(8), null).get("body");

        assertThat(value).isInstanceOf(LobValue.class);
        LobValue lob = (LobValue) value;
        assertThat(lob.kind()).isEqualTo(LobValue.Kind.BINARY);
        try (var in = lob.openStream()) {
            assertThat(in.readAllBytes()).hasSize(64);
        }
        verify(rs, never()).getBytes(anyInt());
        lob.release();
    }

    @Test
    void disabledPolicyKeepsTheOldGetStringPath() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("legacy");

        Object value = read(rs, Types.CLOB, "CLOB", LobReadPolicy.disabled(), null).get("body");

        assertThat(value).isEqualTo("legacy");
        verify(rs).getString(1);
    }

    @Test
    void forceInlineColumnAboveTheCeilingFailsBeforeReading() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                        1024, 8192, List.of("id"))
                .withDeclaredSizes(Map.of("id", 100_000L));

        assertThatThrownBy(() -> JdbcRowMapper.readRowInto(rs, List.of("id"),
                List.of(Types.VARCHAR), List.of("VARCHAR"), DatabaseType.MYSQL,
                new LinkedHashMap<>(), policy, null))
                .isInstanceOf(LobLimitExceededException.class)
                .hasMessageContaining("t_doc.id");
        verify(rs, never()).getString(anyInt());
    }

    @Test
    void spillModeWritesToDiskAndTheSessionDeletesTheFile() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getCharacterStream(1)).thenReturn(new StringReader("y".repeat(100)));
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.SPILL,
                8, 1 << 20, List.of());

        try (LobSpillSession session = LobSpillStore.of(dir, 1 << 20).open()) {
            Object value = read(rs, Types.CLOB, "CLOB", policy, session).get("body");
            assertThat(value).isInstanceOf(LobValue.class);
            assertThat(((LobValue) value).replayable()).isTrue();
            assertThat(readAll(((LobValue) value).openReader())).hasSize(100);
            assertThat(fileCount()).isEqualTo(1);
        }
        assertThat(fileCount()).isZero();
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }
}
