package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The audit page builds its action label as {@code audit.action.<enum name>}. The enum name is
 * never spelled out at the call site, so the literal scan in
 * {@code JavaKeyLiteralsExistInBothBundlesTest} cannot see the key — a missing entry renders as
 * {@code ??audit.action.DIAGNOSTICS_EXPORT_zh_CN??} on the audit page instead.
 */
class AuditActionLabelsTest {

    private static final String EN = "i18n/messages.properties";
    private static final String ZH = "i18n/messages_zh_CN.properties";

    @Test
    void everyActionHasALabelInBothBundles() throws Exception {
        Set<String> en = keysOf(EN);
        Set<String> zh = keysOf(ZH);

        for (AuditAction action : AuditAction.values()) {
            String key = "audit.action." + action.name();
            assertThat(en).as("English label for %s", action).contains(key);
            assertThat(zh).as("Chinese label for %s", action).contains(key);
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
