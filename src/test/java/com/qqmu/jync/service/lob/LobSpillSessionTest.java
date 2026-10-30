package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spilling exists so a value can be bound more than once without living in the heap.
 * The session owns the files: closing it must delete every one, including the ones the
 * caller never released, because a failed sync has no other cleanup hook.
 */
class LobSpillSessionTest {

    @TempDir
    Path dir;

    private static String read(Reader reader) throws IOException {
        try (Reader r = reader) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[32];
            int n;
            while ((n = r.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
            return sb.toString();
        }
    }

    private static long fileCount(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void spilledTextIsReplayableAndDeletedWhenTheSessionCloses() throws IOException {
        LobSpillSession session = LobSpillStore.of(dir, 1 << 20).open();
        LobValue value = session.spillText(new StringReader("hello 中文"));

        assertThat(value.kind()).isEqualTo(LobValue.Kind.TEXT);
        assertThat(value.replayable()).isTrue();
        assertThat(value.length()).isEqualTo(8);
        assertThat(read(value.openReader())).isEqualTo("hello 中文");
        assertThat(read(value.openReader())).isEqualTo("hello 中文");
        assertThat(fileCount(dir)).isEqualTo(1);

        session.close();
        assertThat(fileCount(dir)).isZero();
    }

    @Test
    void spilledBinaryStreamsItsBytesAndIsDeletedWithTheSession() throws IOException {
        LobSpillSession session = LobSpillStore.of(dir, 1 << 20).open();
        LobValue value = session.spillBinary(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertThat(value.kind()).isEqualTo(LobValue.Kind.BINARY);
        try (InputStream in = value.openStream()) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }

        session.close();
        assertThat(fileCount(dir)).isZero();
    }

    @Test
    void exceedingTheSessionCapFailsWithTheSettingAndDirectory() {
        LobSpillSession session = LobSpillStore.of(dir, 4).open();
        assertThatThrownBy(() -> session.spillBinary(
                new java.io.ByteArrayInputStream(new byte[16])))
                .isInstanceOf(LobLimitExceededException.class)
                .hasMessageContaining("sync.lob.spill-max-bytes")
                .hasMessageContaining(dir.toString());
    }

    /**
     * The session cap is a disk cap, so a text value counts by the bytes it occupies in the
     * spill file. Counting every char as three bytes (the UTF-8 worst case) made an ASCII
     * value trip at a third of the configured size and fail the table with a message that
     * blamed a value which in fact fit.
     */
    @Test
    void theTextCapCountsTheBytesOnDiskNotAWorstCaseEstimate() throws IOException {
        LobSpillSession session = LobSpillStore.of(dir, 1_000).open();

        LobValue value = session.spillText(new StringReader("a".repeat(500)));

        assertThat(value.length()).isEqualTo(500);
        assertThat(session.spilledBytes()).isEqualTo(500);
        session.close();
    }

    /** The cap is not off either: two bytes per CJK char still has to count. */
    @Test
    void aTextValueThatReallyExceedsTheCapIsStillRejected() {
        LobSpillSession session = LobSpillStore.of(dir, 1_000).open();

        assertThatThrownBy(() -> session.spillText(new StringReader("中".repeat(400))))
                .isInstanceOf(LobLimitExceededException.class)
                .hasMessageContaining("sync.lob.spill-max-bytes");
    }

    /** A surrogate pair is one 4-byte character, not two 3-byte ones. */
    @Test
    void aSurrogatePairCountsAsFourBytes() throws IOException {
        LobSpillSession session = LobSpillStore.of(dir, 40).open();

        LobValue value = session.spillText(new StringReader("😀".repeat(10)));

        assertThat(value.length()).isEqualTo(20);
        assertThat(session.spilledBytes()).isEqualTo(40);
        session.close();
    }

    @Test
    void sessionCloseIsIdempotent() throws IOException {
        LobSpillSession session = LobSpillStore.of(dir, 1 << 20).open();
        session.spillText(new StringReader("x"));
        session.close();
        session.close();
        assertThat(fileCount(dir)).isZero();
    }
}
