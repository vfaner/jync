package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
     * A key defined twice in one bundle is resolved silently — the last definition wins.
     * Editing the first copy then looks like it does nothing, and the two copies can drift
     * into saying different things. The parity test above compares key <em>sets</em>, so it
     * cannot see this; only a count can.
     */
    @Test
    @DisplayName("neither bundle defines the same key twice")
    void noBundleDefinesAKeyTwice() throws Exception {
        assertThat(duplicateKeysOf(EN)).as("duplicate keys in %s", EN).isEmpty();
        assertThat(duplicateKeysOf(ZH)).as("duplicate keys in %s", ZH).isEmpty();
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

    /**
     * A hint that ends with a full stop in one language and stops mid-air in the other reads as
     * an unfinished sentence next to a finished one — the two bundles are shown side by side in
     * translation review, and the odd one out is usually a sentence whose tail was dropped.
     *
     * <p>Ellipses are not sentence ends: {@code …} and {@code ...} are both how each language
     * writes "still working", so a trailing run of them is stripped before comparing.
     */
    @Test
    @DisplayName("a sentence ends the way its translation ends")
    void sentencesEndTheWayTheirTranslationDoes() throws Exception {
        Map<String, String> en = valuesOf(EN);
        Map<String, String> zh = valuesOf(ZH);

        Map<String, String> mismatched = new TreeMap<>();
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String translated = zh.get(entry.getKey());
            if (translated != null && endsSentence(entry.getValue()) != endsSentence(translated)) {
                mismatched.put(entry.getKey(), translated + "   ‖   " + entry.getValue());
            }
        }

        assertThat(mismatched)
                .as("one language writes the closing full stop, the other does not")
                .isEmpty();
    }

    /** Whether {@code text} is closed off at all — a full stop or the "and so on" ellipsis. */
    private static boolean endsSentence(String text) {
        String tail = text.strip();
        return tail.endsWith(".") || tail.endsWith("!") || tail.endsWith("?")
                || tail.endsWith("…")
                || tail.endsWith("。") || tail.endsWith("！") || tail.endsWith("？");
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

    /** The {@code key=value} pairs of {@code classpathResource}, same line format as {@link #keysOf}. */
    static Map<String, String> valuesOf(String classpathResource) throws Exception {
        byte[] bytes = new ClassPathResource(classpathResource).getInputStream().readAllBytes();
        Map<String, String> values = new TreeMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return values;
    }

    /** Keys defined more than once in {@code body}, from the same line format as {@link #keysOf}. */
    static Set<String> duplicateKeysOf(String classpathResource) throws Exception {
        byte[] bytes = new ClassPathResource(classpathResource).getInputStream().readAllBytes();
        Map<String, Integer> counts = new TreeMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                counts.merge(trimmed.substring(0, eq).trim(), 1, Integer::sum);
            }
        }
        Set<String> duplicates = new TreeSet<>();
        counts.forEach((key, count) -> {
            if (count > 1) {
                duplicates.add(key);
            }
        });
        return duplicates;
    }

    private static Set<String> missing(Set<String> wanted, Set<String> present) {
        Set<String> diff = new TreeSet<>(wanted);
        diff.removeAll(present);
        return diff;
    }
}
