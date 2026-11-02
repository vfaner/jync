package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The connection form renders {@link MigrationConnectionKind} and
 * {@link MigrationConnectionAuthType} labels through computed keys
 * ({@code migration.kind.<NAME>} / {@code migration.auth.<NAME>}). A missing key shows up as
 * {@code ??migration.kind.X_zh_CN??} in the dropdown instead of failing at build time.
 *
 * <p>Reads the files rather than {@link java.util.ResourceBundle}, for the same reason as
 * {@link MigrationTypeLabelTest}: ResourceBundle's parent fallback would hide a key missing from
 * the Chinese bundle.
 */
class MigrationConnectionKindLabelTest {

    private static final String EN = "i18n/messages.properties";
    private static final String ZH = "i18n/messages_zh_CN.properties";

    @Test
    void everyKindHasALabelInBothBundles() throws Exception {
        Set<String> en = keysOf(EN);
        Set<String> zh = keysOf(ZH);

        for (MigrationConnectionKind kind : MigrationConnectionKind.values()) {
            String key = "migration.kind." + kind.name();
            assertThat(en).as("English label for %s", kind).contains(key);
            assertThat(zh).as("Chinese label for %s", kind).contains(key);
        }
    }

    @Test
    void everyAuthTypeHasALabelInBothBundles() throws Exception {
        Set<String> en = keysOf(EN);
        Set<String> zh = keysOf(ZH);

        for (MigrationConnectionAuthType auth : MigrationConnectionAuthType.values()) {
            String key = "migration.auth." + auth.name();
            assertThat(en).as("English label for %s", auth).contains(key);
            assertThat(zh).as("Chinese label for %s", auth).contains(key);
        }
    }

    private static Set<String> keysOf(String path) throws Exception {
        String text = new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Set<String> keys = new TreeSet<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                keys.add(trimmed.substring(0, trimmed.indexOf('=')).trim());
            }
        }
        return keys;
    }
}
