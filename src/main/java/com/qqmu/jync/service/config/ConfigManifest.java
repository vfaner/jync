package com.qqmu.jync.service.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code manifest.json}: one line per entry, so an importer can report which entry failed before
 * parsing any of them.
 *
 * <p>It describes every entry except itself — a file cannot contain its own hash. Sizes and
 * digests are of the <em>uncompressed</em> bytes, which is what the reader sees after inflating,
 * so the check does not depend on the zip encoder's settings.
 */
public record ConfigManifest(int schemaVersion, int entryCount, List<Entry> entries) {

    public record Entry(String name, long size, String sha256) {
    }

    /** Builds the manifest from the entries that will be zipped, minus any previous manifest. */
    public static ConfigManifest of(Map<String, byte[]> entries) {
        List<Entry> items = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
            if ("manifest.json".equals(entry.getKey())) {
                continue;
            }
            items.add(new Entry(entry.getKey(), entry.getValue().length,
                    ConfigPackageWriter.sha256Hex(entry.getValue())));
        }
        return new ConfigManifest(ConfigPackageFormat.SCHEMA_VERSION, items.size(), items);
    }
}
