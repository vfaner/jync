package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Every dialect family must have a label in both bundles. A family added to the enum and
 * forgotten here renders as {@code ??db.family.X??} in the connection form's family picker.
 *
 * <p>Reads the files rather than {@link java.util.ResourceBundle}: a bundle loaded through
 * ResourceBundle falls back to its parent, so {@code containsKey} would report the English
 * label for a family missing from the Chinese bundle. {@code I18nBundleParityTest} keeps the
 * two key sets equal; this test keeps the enum inside that set.
 */
class DialectFamilyLabelTest {

    private static final String EN = "i18n/messages.properties";
    private static final String ZH = "i18n/messages_zh_CN.properties";

    @Test
    void everyFamilyHasALabelInBothBundles() throws Exception {
        Set<String> en = keysOf(EN);
        Set<String> zh = keysOf(ZH);

        for (DatabaseType.DialectFamily family : DatabaseType.DialectFamily.values()) {
            String key = "db.family." + family.name();
            assertThat(en).as("English label for %s", family).contains(key);
            assertThat(zh).as("Chinese label for %s", family).contains(key);
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
