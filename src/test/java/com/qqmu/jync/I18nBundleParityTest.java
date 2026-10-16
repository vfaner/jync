package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The keys in the two message bundles are the same set.
 *
 * <p>Nothing else in this project reads the Chinese bundle. Every English test that asserts
 * {@code doesNotContain("??")} catches a key missing from the <em>English</em> bundle, on the pages
 * it happens to render — but the mirror-image mistake, a key added to {@code messages.properties}
 * and forgotten in {@code messages_zh_CN.properties}, is invisible here and shows up only on a
 * Chinese user's screen as {@code ??settings.newThing??}. Adding a key by hand to one file and not
 * the other is the ordinary way that happens.
 *
 * <p>Checks key presence, not values: a translation is allowed to differ, and the placeholder
 * arguments are the translator's business.
 */
class I18nBundleParityTest {

    private static final String EN = "i18n/messages.properties";
    private static final String ZH = "i18n/messages_zh_CN.properties";

    @Test
    @DisplayName("both bundles define exactly the same keys")
    void bothBundlesDefineTheSameKeys() throws Exception {
        Set<String> en = keysOf(EN);
        Set<String> zh = keysOf(ZH);

        assertThat(missing(en, zh))
                .as("keys in %s with no value in %s — a Chinese user sees ??key?? for these", EN, ZH)
                .isEmpty();
        assertThat(missing(zh, en))
                .as("keys in %s with no value in %s — dead weight, or a rename that only landed in one",
                        ZH, EN)
                .isEmpty();
    }

    /**
     * A guard that cannot fail is not a guard: this pins that the comparison above would notice
     * drift, using a key that exists in neither bundle. Without it, a parsing bug that returned two
     * empty sets would make the parity test pass for any pair of files.
     */
    @Test
    @DisplayName("the parity check would notice a missing key")
    void theParityCheckCanFail() {
        Set<String> en = new LinkedHashSet<>(Set.of("a.one", "a.two"));
        Set<String> zh = new LinkedHashSet<>(Set.of("a.one"));

        assertThat(missing(en, zh)).containsExactly("a.two");
        assertThat(missing(en, en)).isEmpty();
    }

    /** The keys defined in {@code body}: one per non-comment line of the form {@code key=value}. */
    static Set<String> keysOf(String classpathResource) throws Exception {
        byte[] bytes = new ClassPathResource(classpathResource).getInputStream().readAllBytes();
        Set<String> keys = new TreeSet<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                keys.add(trimmed.substring(0, eq).trim());
            }
        }
        return keys;
    }

    private static Set<String> missing(Set<String> wanted, Set<String> present) {
        Set<String> diff = new TreeSet<>(wanted);
        diff.removeAll(present);
        return diff;
    }
}
