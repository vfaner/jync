package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The zip half of an export: plain, sorted, deterministic entries and a stable digest. */
class ConfigPackageWriterTest {

    private final ConfigPackageWriter writer = new ConfigPackageWriter();

    @Test
    @DisplayName("entries are written in name order so two exports of the same data match")
    void entriesAreSorted() throws IOException {
        Map<String, byte[]> unsorted = new LinkedHashMap<>();
        unsorted.put("state/b.json", "B".getBytes(StandardCharsets.UTF_8));
        unsorted.put("config/a.json", "A".getBytes(StandardCharsets.UTF_8));

        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(writer.zip(unsorted)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }

        assertThat(names).containsExactly("config/a.json", "state/b.json");
    }

    @Test
    @DisplayName("the digest is the lower-case hex SHA-256 of the bytes")
    void sha256IsCanonical() {
        assertThat(ConfigPackageWriter.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ConfigPackageWriter.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
