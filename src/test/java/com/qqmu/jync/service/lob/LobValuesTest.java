package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three shapes one column value can take while it travels from a result set to a
 * prepared statement.
 *
 * <p>{@code InMemory} and {@code Spilled} are replayable because an emulated upsert binds
 * the same value several times; {@code OneShot} wraps a live result-set stream, so it must
 * refuse a second read rather than silently return an exhausted reader. Release is the
 * only place a spill file is deleted, so it has to be idempotent and complete.
 */
class LobValuesTest {

    @TempDir
    Path dir;

    private static String read(Reader reader) throws IOException {
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

    @Test
    void inMemoryTextIsReplayable() throws IOException {
        LobValue value = LobValues.text("中文abc");
        assertThat(value.kind()).isEqualTo(LobValue.Kind.TEXT);
        assertThat(value.length()).isEqualTo(5);
        assertThat(value.replayable()).isTrue();
        assertThat(read(value.openReader())).isEqualTo("中文abc");
        assertThat(read(value.openReader())).isEqualTo("中文abc");
        value.release();
        value.release();
    }

    @Test
    void inMemoryBinaryIsReplayable() throws IOException {
        byte[] bytes = {1, 2, 3};
        LobValue value = LobValues.binary(bytes);
        assertThat(value.kind()).isEqualTo(LobValue.Kind.BINARY);
        assertThat(value.length()).isEqualTo(3);
        assertThat(value.replayable()).isTrue();
        try (var in = value.openStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        try (var in = value.openStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void oneShotTextRefusesASecondRead() throws IOException {
        LobValue value = LobValues.oneShotText(new StringReader("abc"), 3);
        assertThat(value.replayable()).isFalse();
        assertThat(value.length()).isEqualTo(3);
        assertThat(read(value.openReader())).isEqualTo("abc");
        assertThatThrownBy(value::openReader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been read");
    }

    @Test
    void oneShotReleaseClosesTheUnderlyingStream() {
        Reader reader = new StringReader("abc");
        LobValue value = LobValues.oneShotText(reader, 3);
        value.release();
        assertThatThrownBy(reader::read).isInstanceOf(IOException.class);
    }

    @Test
    void spilledValueReadsRepeatedlyAndDeletesOnRelease() throws IOException {
        Path file = Files.createTempFile(dir, "lob-", ".bin");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
        LobValue value = LobValues.spilled(file, LobValue.Kind.TEXT, 5);

        assertThat(value.replayable()).isTrue();
        assertThat(read(value.openReader())).isEqualTo("hello");
        assertThat(read(value.openReader())).isEqualTo("hello");

        value.release();
        assertThat(Files.exists(file)).isFalse();
        value.release();
    }

    @Test
    void spilledBinaryStreamsItsBytes() throws IOException {
        Path file = Files.createTempFile(dir, "lob-", ".bin");
        Files.write(file, new byte[] {9, 8, 7});
        LobValue value = LobValues.spilled(file, LobValue.Kind.BINARY, 3);
        try (var in = value.openStream()) {
            assertThat(in.readAllBytes()).containsExactly(9, 8, 7);
        }
        value.release();
    }

    @Test
    void helpersRecognizeAndReleaseAnyLobValue() {
        LobValue value = LobValues.text("x");
        assertThat(LobValues.isLob(value)).isTrue();
        assertThat(LobValues.isLob("x")).isFalse();
        assertThat(LobValues.isReplayable(value)).isTrue();
        assertThat(LobValues.isReplayable("x")).isTrue();
        LobValues.release(value);
        LobValues.release("x");
    }
}
