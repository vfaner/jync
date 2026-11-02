package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Every migration type must have a name and a description in both bundles. The landing page
 * renders both through computed keys ({@code messageKey()} / {@code descriptionKey()}), so a
 * wrong suffix shows up as {@code ??migration.type.x.name.description_zh_CN??} on the card
 * instead of failing at build time.
 *
 * <p>Reads the files rather than {@link java.util.ResourceBundle}: a bundle loaded through
 * ResourceBundle falls back to its parent, so {@code containsKey} would report the English
 * label for a key missing from the Chinese bundle.
 */
class MigrationTypeLabelTest {

    private static final String EN = "i18n/messages.properties";
    private static final String ZH = "i18n/messages_zh_CN.properties";

    @Test
    void everyTypeHasANameAndDescriptionInBothBundles() throws Exception {
        Set<String> en = keysOf(EN);
        Set<String> zh = keysOf(ZH);

        for (MigrationType type : MigrationType.values()) {
            assertThat(en).as("English name for %s", type).contains(type.messageKey());
            assertThat(zh).as("Chinese name for %s", type).contains(type.messageKey());
            assertThat(en).as("English description for %s", type)
                    .contains(type.descriptionKey());
            assertThat(zh).as("Chinese description for %s", type)
                    .contains(type.descriptionKey());
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
