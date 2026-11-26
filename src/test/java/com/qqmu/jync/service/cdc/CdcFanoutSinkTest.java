package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.lob.LobValue;

/**
 * The fan-out transport serializes the row map, so a spilled LOB handle must be materialized
 * before the event is published; Java serialization cannot write the file reference and the
 * transaction would never leave the capture.
 */
class CdcFanoutSinkTest {

    private static final class TextLob implements LobValue {
        private final String value;

        private TextLob(String value) {
            this.value = value;
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }

        @Override
        public long length() {
            return value.length();
        }

        @Override
        public boolean replayable() {
            return true;
        }

        @Override
        public Reader openReader() {
            return new StringReader(value);
        }

        @Override
        public java.io.InputStream openStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release() {
        }
    }

    @Test
    void aSpilledLobIsMaterializedIntoSerializableText() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("payload", new TextLob("a large value"));

        Map<String, Object> wire = CdcFanoutSink.wireRow(row);

        assertThat(wire).containsEntry("id", 1L).containsEntry("payload", "a large value");
        // The original map is left untouched: the spiller still owns cleanup of its handle.
        assertThat(row.get("payload")).isInstanceOf(TextLob.class);
    }

    @Test
    void aRowWithoutLobsIsPassedThroughUnchanged() throws Exception {
        Map<String, Object> row = Map.of("id", 1L, "name", "x");

        assertThat(CdcFanoutSink.wireRow(row)).isSameAs(row);
    }
}
